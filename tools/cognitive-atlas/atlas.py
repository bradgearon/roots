#!/usr/bin/env python3
"""GGUF-first physical inventory and reversible causal ablation. Python 3.10+."""
import argparse
import csv
import hashlib
import json
import math
import re
import shutil
import statistics
import struct
import subprocess
import tempfile
from pathlib import Path

# ggml_type: (name, weights per block, bytes per block).
# Only these encodings are supported for all-zero block replacement.
TYPES = {0:("F32",1,4),1:("F16",1,2),2:("Q4_0",32,18),
         3:("Q4_1",32,20),6:("Q5_0",32,22),7:("Q5_1",32,24),
         8:("Q8_0",32,34),10:("Q2_K",256,84),11:("Q3_K",256,110),
         12:("Q4_K",256,144),13:("Q5_K",256,176),14:("Q6_K",256,210),
         15:("Q8_K",256,292),30:("BF16",1,2)}
KV = {0:"B",1:"b",2:"H",3:"h",4:"I",5:"i",6:"f",7:"?",10:"Q",11:"q",12:"d"}
COPY = 1024*1024
ROLES = re.compile(r"^blk\.(\d+)\.(attn_(?:q|k|v|output)|ffn_(?:gate|up|down))\.weight$")

def digest(path):
    h=hashlib.sha256()
    with open(path,"rb") as f:
        for chunk in iter(lambda:f.read(COPY),b""): h.update(chunk)
    return h.hexdigest()

def inventory(path):
    size=path.stat().st_size
    with path.open("rb") as f:
        def read(n):
            if n<0 or n>size-f.tell(): raise ValueError("truncated GGUF")
            data=f.read(n)
            if len(data)!=n: raise ValueError("truncated GGUF")
            return data
        def skip(n):
            if n<0 or n>size-f.tell(): raise ValueError("truncated metadata")
            f.seek(n,1)
        def num(fmt):
            return struct.unpack("<"+fmt,read(struct.calcsize("<"+fmt)))[0]
        def string(keep=True):
            n=num("Q")
            if not keep: skip(n); return None
            if n>16*1024*1024: raise ValueError("oversized GGUF string")
            return read(n).decode("utf-8")
        def value(t, keep):
            if t in KV:
                if keep: return num(KV[t])
                skip(struct.calcsize("<"+KV[t])); return None
            if t==8: return string(keep)
            if t==9:
                subtype,n=num("I"),num("Q")
                if subtype in KV: skip(n*struct.calcsize("<"+KV[subtype]))
                elif subtype==8:
                    for _ in range(n): string(False)
                else: raise ValueError("unsupported metadata array")
                return {"array_type":subtype,"count":n} if keep else None
            raise ValueError("unknown metadata type")
        if read(4)!=b"GGUF" or num("I")!=3: raise ValueError("only little-endian GGUF v3 supported")
        nt,nk=num("Q"),num("Q")
        if nt>100000 or nk>100000: raise ValueError("unreasonable GGUF header")
        meta={}
        for _ in range(nk):
            key=string(); t=num("I")
            if key in meta: raise ValueError("duplicate metadata key")
            keep=key.startswith(("general.","gemma3.","split."))
            v=value(t,keep)
            if keep: meta[key]=v
        if meta.get("split.count",1)!=1: raise ValueError("split GGUF not supported")
        alignment=meta.get("general.alignment",32)
        if not isinstance(alignment,int) or alignment<1 or alignment>65536 or alignment&(alignment-1):
            raise ValueError("invalid alignment")
        tensors=[]
        names=set()
        for _ in range(nt):
            name=string(); nd=num("I")
            if name in names or not 1<=nd<=4: raise ValueError("invalid tensor descriptor")
            names.add(name)
            dims=[num("Q") for _ in range(nd)]
            typ,offset=num("I"),num("Q")
            if not all(dims) or offset%alignment: raise ValueError("bad tensor shape/alignment")
            spec=TYPES.get(typ)
            nbytes=None; row=None
            if spec:
                if dims[0]%spec[1]: raise ValueError("unaligned quantization row: "+name)
                row=dims[0]//spec[1]*spec[2]
                nbytes=row*math.prod(dims[1:])
            m=ROLES.match(name)
            tensors.append(dict(name=name,dims=dims,type_id=typ,type=spec[0] if spec else "unsupported",
                                offset=offset,nbytes=nbytes,row_bytes=row,
                                layer=int(m[1]) if m else None,
                                role=m[2] if m else "other"))
        start=(f.tell()+alignment-1)//alignment*alignment
        ordered=sorted(tensors,key=lambda t:t["offset"])
        for i,t in enumerate(ordered):
            pos=start+t["offset"]; end=start+ordered[i+1]["offset"] if i+1<len(ordered) else size
            if pos>=end or end>size or (t["nbytes"] is not None and pos+t["nbytes"]>end):
                raise ValueError("overlapping or truncated tensor: "+t["name"])
            t["file_offset"]=pos
            t["allocated_span"]=end-pos
        return dict(format="GGUF-v3-LE",file_size=size,data_start=start,metadata=meta,tensors=tensors)

def ranges_for(inv, scope, chunks, match):
    groups={}
    for t in inv["tensors"]:
        if t["layer"] is None or not re.search(match,t["name"]): continue
        if t["nbytes"] is None: continue
        role="attention" if t["role"].startswith("attn_") else "ffn"
        label="blk.%d.%s"%(t["layer"],role)
        if scope=="layer": label="blk.%d"%t["layer"]
        if scope in ("tensor","chunk"): label=t["name"]
        nrows=t["nbytes"]//t["row_bytes"]
        count=min(chunks,nrows) if scope=="chunk" else 1
        for j in range(count):
            lo,hi=j*nrows//count,(j+1)*nrows//count
            key=label+(".rows%d-%d"%(lo,hi) if scope=="chunk" else "")
            groups.setdefault(key,[]).append(dict(tensor=t["name"],first_row=lo,row_count=hi-lo,
                offset=t["file_offset"]+lo*t["row_bytes"],nbytes=(hi-lo)*t["row_bytes"],type=t["type"]))
    return groups

def transfer(src,dst,ranges,zero=False):
    for r in ranges:
        src.seek(r["offset"]); dst.seek(r["offset"]); left=r["nbytes"]
        while left:
            n=min(COPY,left)
            data=bytes(n) if zero else src.read(n)
            if len(data)!=n: raise IOError("truncated tensor during restore")
            dst.write(data); left-=n

def load_tasks(path,split,taskfile):
    cases=[]
    with path.open(encoding="utf-8",newline="") as f:
        for i,r in enumerate(csv.DictReader(f,delimiter="\t")):
            if split!="all" and r["split"]!=split: continue
            if r["correct"]==r["wrong"] or r["split"] not in ("discovery","holdout"):
                raise ValueError("invalid task")
            cases.append(dict(id=str(i),**r))
    if not cases: raise ValueError("empty task selection")
    with taskfile.open("w",encoding="utf-8",newline="\n") as f:
        for c in cases:
            for j,(a,b) in enumerate(((c["correct"],c["wrong"]),(c["wrong"],c["correct"]))):
                q="Choose the better answer. "+c["question"]+"|A. "+a+"|B. "+b+"|Answer with only A or B."
                if any(x in q for x in ("\t","\n","\r")): raise ValueError("task contains control separator")
                f.write(c["id"]+":"+str(j)+"\t"+q+"\n")
    return cases

def score(exe,model,taskfile,cases,args):
    cp=subprocess.run([str(exe),str(model),str(taskfile),args.mode,str(args.threads),str(args.gpu_layers)],
                      capture_output=True,text=True,encoding="utf-8",errors="replace")
    if cp.returncode: raise RuntimeError(cp.stderr[-4000:])
    scores={}
    for line in cp.stdout.splitlines():
        key,val=line.split("\t")
        if key in scores: raise ValueError("duplicate scorer result")
        scores[key]=float(val)
    if set(scores)!={c["id"]+":"+str(j) for c in cases for j in range(2)} or not all(map(math.isfinite,scores.values())):
        raise ValueError("invalid/incomplete scorer output")
    out=[]
    for c in cases:
        a,b=scores[c["id"]+":0"],-scores[c["id"]+":1"]
        out.append(dict(id=c["id"],category=c["category"],split=c["split"],margin=(a+b)/2,
                        accuracy=(int(a>0)+int(b>0))/2,orders=[a,b]))
    return out

def summarize(base,other,repeat):
    results=[]
    for category,split in sorted({(x["category"],x["split"]) for x in base}):
        triples=[(a,b,c) for a,b,c in zip(base,other,repeat) if a["category"]==category and a["split"]==split]
        d=[a["margin"]-b["margin"] for a,b,c in triples]
        noise=[abs(a["margin"]-c["margin"]) for a,b,c in triples]
        results.append(dict(category=category,split=split,n=len(triples),
             baseline_accuracy=statistics.mean(a["accuracy"] for a,b,c in triples),
             baseline_margin=statistics.mean(a["margin"] for a,b,c in triples),
             ablated_accuracy=statistics.mean(b["accuracy"] for a,b,c in triples),
             margin_loss=statistics.mean(d),margin_loss_sd=statistics.stdev(d) if len(d)>1 else None,
             repeat_noise=statistics.mean(noise)))
    for x in results:
        controls=[y["margin_loss"] for y in results if y["split"]==x["split"] and y["category"]!=x["category"]]
        x["selectivity"]=x["margin_loss"]-statistics.median(controls) if controls else None
    return results

def save(path,doc):
    temp=path.with_suffix(path.suffix+".tmp")
    temp.write_text(json.dumps(doc,indent=2,allow_nan=False),encoding="utf-8")
    temp.replace(path)

def scan(args,inv):
    if inv["metadata"].get("general.architecture")!="gemma3":
        raise ValueError("scan currently requires a gemma3 text GGUF")
    if args.limit<1 or args.chunks<1 or args.threads<1 or args.gpu_layers<0: raise ValueError("invalid scan limits")
    groups=ranges_for(inv,args.scope,args.chunks,args.match)
    if not groups: raise ValueError("no supported matching tensor ranges")
    keys=list(groups)
    if args.region:
        if len(set(args.region))!=len(args.region) or any(k not in groups for k in args.region):
            raise ValueError("unknown or duplicate requested region; use plan with identical scope/chunks")
        keys=args.region
    if args.region and len(keys)>args.limit: raise ValueError("raise --limit to include explicit regions")
    if len(keys)>args.limit:
        keys=[keys[i*(len(keys)-1)//max(1,args.limit-1)] for i in range(args.limit)]
    source=args.model.resolve(); exe=args.scorer.resolve()
    if not exe.is_file(): raise ValueError("scorer executable not found")
    output=args.out.resolve()
    if output.exists() or output==source or output==args.tasks.resolve(): raise ValueError("output must be a new path")
    output.parent.mkdir(parents=True,exist_ok=True)
    root=args.scratch.resolve() if args.scratch else output.parent
    root.mkdir(parents=True,exist_ok=True)
    if shutil.disk_usage(root).free<source.stat().st_size+16*COPY: raise ValueError("insufficient scratch disk space")
    report=dict(schema="roots.atlas.gguf/1",source_sha256=digest(source),scorer_sha256=digest(exe),
                tasks_sha256=digest(args.tasks),inventory=inv,mode=args.mode,scope=args.scope,split=args.split,
                threads=args.threads,gpu_layers=args.gpu_layers,selected=keys,scope_match=args.match,chunks=args.chunks,experiments=[],status="running")
    with tempfile.TemporaryDirectory(prefix="roots-atlas-",dir=root) as directory:
        temp=Path(directory); tasks=temp/"tasks.tsv"; variant=temp/"variant.gguf"
        cases=load_tasks(args.tasks,args.split,tasks)
        shutil.copyfile(source,variant)
        # Baseline the scratch copy twice using the same process configuration as interventions.
        base=score(exe,variant,tasks,cases,args); repeat=score(exe,variant,tasks,cases,args)
        report.update(baseline=base,baseline_repeat=repeat)
        save(output,report)
        try:
            for key in keys:
                rr=groups[key]
                print("Testing",key,flush=True)
                with source.open("rb") as src,variant.open("r+b") as dst: transfer(src,dst,rr,True)
                try:
                    actual=score(exe,variant,tasks,cases,args)
                    report["experiments"].append(dict(id=key,ranges=rr,results=summarize(base,actual,repeat),per_case=actual))
                finally:
                    with source.open("rb") as src,variant.open("r+b") as dst: transfer(src,dst,rr)
                save(output,report)
            if digest(variant)!=report["source_sha256"] or digest(source)!=report["source_sha256"]:
                raise ValueError("source/restore hash mismatch")
            report["status"]="complete"
        except BaseException:
            report["status"]="incomplete"
            raise
        finally: save(output,report)

def extract(args,inv):
    # Emit a manifest and opaque bytes, NOT a runnable GGUF or a capability claim.
    groups=ranges_for(inv,args.scope,args.chunks,args.match)
    if args.region not in groups: raise ValueError("unknown region; use plan")
    if args.out.exists(): raise ValueError("output directory must not exist")
    args.out.mkdir(parents=True)
    rr=groups[args.region]
    with args.model.open("rb") as f:
        for i,r in enumerate(rr):
            name="part-%03d.bin"%i; p=args.out/name
            f.seek(r["offset"]); left=r["nbytes"]
            with p.open("wb") as out:
                while left:
                    data=f.read(min(COPY,left))
                    if not data: raise IOError("truncated source")
                    out.write(data); left-=len(data)
            r.update(file=name,sha256=digest(p))
    save(args.out/"manifest.json",dict(source_sha256=digest(args.model),region=args.region,ranges=rr,
         inventory_format=inv["format"],warning="Opaque weight fragments; not a runnable model. Original license applies."))

def rank(args):
    report=json.loads(args.report.read_text(encoding="utf-8"))
    if report["status"]!="complete": raise ValueError("scan is not complete")
    by_category={}
    for e in report["experiments"]:
        for x in e["results"]:
            # Screen out abilities the unmodified model did not demonstrate, and broad collapse.
            if x["baseline_accuracy"]<0.75 or x["baseline_margin"]<=0: continue
            if x["selectivity"] is None: continue
            if x["margin_loss"]<=max(0.0,2*x["repeat_noise"]) or x["selectivity"]<=0: continue
            by_category.setdefault(x["category"]+"/"+x["split"],[]).append({
                "region":e["id"],"bytes":sum(r["nbytes"] for r in e["ranges"]),**x})
    for k in by_category:
        by_category[k]=sorted(by_category[k],key=lambda x:x["selectivity"],reverse=True)[:args.top]
    print(json.dumps(dict(status="exploratory, not proof; compare independently held-out tasks",
                          source_sha256=report["source_sha256"],categories=by_category),indent=2))

def selftest():
    # Construct a valid tiny GGUF directory with an aligned Q8_0 tensor and a float tensor.
    def st(s):
        b=s.encode();return struct.pack("<Q",len(b))+b
    with tempfile.TemporaryDirectory() as d:
        p=Path(d)/"fixture.gguf"
        h=b"GGUF"+struct.pack("<IQQ",3,2,1)
        h+=st("general.architecture")+struct.pack("<I",8)+st("gemma3")
        h+=st("blk.0.ffn_up.weight")+struct.pack("<IQQIQ",2,32,2,8,0)
        h+=st("blk.0.attn_q.weight")+struct.pack("<IQQIQ",2,4,2,0,96)
        h+=bytes((-len(h))%32)
        p.write_bytes(h+bytes([17])*68+bytes(28)+bytes([42])*32)
        inv=inventory(p); groups=ranges_for(inv,"chunk",2,".")
        first=groups["blk.0.ffn_up.weight.rows0-1"]
        assert first[0]["nbytes"]==34 and first[0]["offset"]==len(h)
        copy=Path(d)/"copy.gguf";shutil.copyfile(p,copy)
        with p.open("rb") as src,copy.open("r+b") as dst: transfer(src,dst,first,True)
        assert copy.read_bytes()==p.read_bytes()[:len(h)]+bytes(34)+p.read_bytes()[len(h)+34:]
        with p.open("rb") as src,copy.open("r+b") as dst: transfer(src,dst,first)
        assert digest(p)==digest(copy)
    print("PASS: GGUF inventory, exact row ablation, byte-for-byte restoration")

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    sub=ap.add_subparsers(dest="command",required=True)
    sub.add_parser("selftest")
    s=sub.add_parser("rank");s.add_argument("report",type=Path);s.add_argument("--top",type=int,default=5)
    for name in ("inspect","plan","scan","extract"):
        s=sub.add_parser(name);s.add_argument("model",type=Path)
        if name=="inspect": continue
        s.add_argument("--scope",choices=["layer","group","tensor","chunk"],default="group")
        s.add_argument("--chunks",type=int,default=4);s.add_argument("--match",default=".")
        if name=="scan":
            s.add_argument("--scorer",required=True,type=Path);s.add_argument("--out",required=True,type=Path)
            s.add_argument("--tasks",type=Path,default=Path(__file__).with_name("probes.tsv"))
            s.add_argument("--split",choices=["discovery","holdout","all"],default="discovery")
            s.add_argument("--mode",choices=["base","it"],default="it");s.add_argument("--limit",type=int,default=12)
            s.add_argument("--threads",type=int,default=4);s.add_argument("--gpu-layers",type=int,default=0)
            s.add_argument("--scratch",type=Path);s.add_argument("--region",action="append")
        elif name=="extract":
            s.add_argument("--region",required=True);s.add_argument("--out",type=Path,required=True)
    args=ap.parse_args()
    if args.command=="selftest": return selftest()
    if args.command=="rank": return rank(args)
    inv=inventory(args.model)
    if args.command=="inspect": print(json.dumps(inv,indent=2))
    elif args.command=="plan": print(json.dumps(ranges_for(inv,args.scope,args.chunks,args.match),indent=2))
    elif args.command=="scan": scan(args,inv)
    else: extract(args,inv)

if __name__=="__main__": main()
