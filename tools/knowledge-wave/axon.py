#!/usr/bin/env python3
"""Local Qwen Element Flow experiments and weight-fragment .axon subscriptions."""
import argparse
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile
import wave
import struct
import shutil

from stage_qwen import QWEN, GGML
BLOCK = 32
CHUNK = 1024*1024
TYPES = {0:(1,4),1:(1,2),2:(32,18),3:(32,20),6:(32,22),7:(32,24),8:(32,34),
         10:(256,84),11:(256,110),12:(256,144),13:(256,176),14:(256,210),
         15:(256,292),30:(1,2)}
KV={0:1,1:1,2:2,3:2,4:4,5:4,6:4,7:1,10:8,11:8,12:8}
HEX=re.compile(r"[a-f0-9]{64}\Z")
def digest(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f,"sha256").hexdigest()

def write_new(path, obj):
    with path.open("x",encoding="utf-8") as f:
        json.dump(obj,f,indent=2,allow_nan=False);f.write("\n")

def inventory(path):
    # Independent bounds/geometry validation against GGUF, not trace text.
    size=path.stat().st_size
    with path.open("rb") as f:
        def read(n):
            if not 0<=n<=size-f.tell(): raise ValueError("truncated GGUF")
            return f.read(n)
        def skip(n):
            if not 0<=n<=size-f.tell(): raise ValueError("truncated GGUF")
            f.seek(n,1)
        def num(fmt): return struct.unpack("<"+fmt,read(struct.calcsize("<"+fmt)))[0]
        def string(keep=False):
            n=num("Q")
            if not keep: skip(n);return
            if n>1024*1024:raise ValueError("oversize name")
            return read(n).decode("utf-8")
        if read(4)!=b"GGUF" or num("I")!=3:raise ValueError("requires GGUF v3 LE")
        nt,nk=num("Q"),num("Q")
        if nt>100000 or nk>100000:raise ValueError("oversize directory")
        alignment=32;split=1;seen=set()
        for _ in range(nk):
            key=string(True);typ=num("I")
            if key in seen:raise ValueError("duplicate metadata")
            seen.add(key)
            if key in ("general.alignment","split.count"):
                if typ not in (2,4):raise ValueError("invalid alignment/split metadata")
                n=num("H" if typ==2 else "I")
                if key=="general.alignment":alignment=n
                else:split=n
            elif typ in KV:skip(KV[typ])
            elif typ==8:string()
            elif typ==9:
                subtype,n=num("I"),num("Q")
                if subtype in KV:skip(n*KV[subtype])
                elif subtype==8:
                    if n>(size-f.tell())//8:raise ValueError("invalid array")
                    for _ in range(n):string()
                else:raise ValueError("unsupported array")
            else:raise ValueError("unknown metadata type")
        if split!=1 or not 1<=alignment<=65536 or alignment&(alignment-1):
            raise ValueError("invalid alignment or split file")
        tensors={}
        for _ in range(nt):
            name=string(True);nd=num("I")
            if name in tensors or not 1<=nd<=4:raise ValueError("invalid descriptor")
            dims=[num("Q") for _ in range(nd)];typ,offset=num("I"),num("Q")
            if not all(dims) or offset%alignment:raise ValueError("invalid tensor")
            block,bytes_=TYPES.get(typ,(0,0))
            row_bytes=None
            if block:
                if dims[0]%block:raise ValueError("bad quantization geometry")
                row_bytes=dims[0]//block*bytes_
            tensors[name]=dict(offset=offset,rows=math.prod(dims[1:]),row_bytes=row_bytes,type_id=typ,dims=dims)
        start=(f.tell()+alignment-1)//alignment*alignment
        entries=sorted(tensors.values(),key=lambda t:t["offset"])
        for i,t in enumerate(entries):
            t["offset"]+=start
            end=start+entries[i+1]["offset"] if i+1<len(entries) else size
            if t["offset"]>=end or end>size or (t["row_bytes"] and t["rows"]*t["row_bytes"]>end-t["offset"]):
                raise ValueError("overlap/truncated tensor")
        return tensors

def files(args):
    result={"talker":args.talker.resolve(),"codec":args.codec.resolve()}
    if result["talker"]==result["codec"]:raise ValueError("distinct GGUFs required")
    return result

def source_hashes(models):
    return {k:{"sha256":digest(p),"bytes":p.stat().st_size} for k,p in models.items()}

def run(args):
    models=files(args)
    exe=args.exe.resolve()
    if not exe.is_file():raise ValueError("instrumented executable missing")
    out=args.out.resolve();out.mkdir(parents=True,exist_ok=False)
    text=args.text_file.read_bytes()
    if not text or len(text)>65536:raise ValueError("text fixture must be 1–65536 bytes")
    text.decode("utf-8")
    if not args.ref_wav and not args.ref_spk:raise ValueError("authorized clone WAV or speaker embedding required")
    if args.ref_rvq and (not args.ref_spk or not args.ref_text):
        raise ValueError("ref-rvq requires ref-spk and ref-text")
    sources=source_hashes(models)
    references={}
    cmd=[str(exe),"--model",str(models["talker"]),"--codec",str(models["codec"]),
         "--lang",args.language,"--seed",str(args.seed),"--max-new",str(args.max_new),
         "-o",str(out/"speech.wav")]
    for name in ("ref_wav","ref_spk","ref_rvq","ref_text"):
        path=getattr(args,name)
        if path:
            path=path.resolve()
            references[name]=digest(path)
            cmd+=["--"+name.replace("_","-"),str(path)]
    if args.greedy:cmd.append("--greedy")
    env=dict(os.environ)
    env.update(ROOTS_WAVE_TALKER=str(models["talker"]),ROOTS_WAVE_CODEC=str(models["codec"]),
               ROOTS_WAVE_TRACE=str(out/"flow.tsv"))
    with (out/"engine.log").open("wb") as log:
        subprocess.run(cmd,input=text,stdout=log,stderr=subprocess.STDOUT,env=env,
                       timeout=args.timeout,check=True)
    with wave.open(str(out/"speech.wav"),"rb") as w:
        if w.getnframes()==0:raise ValueError("empty output")
        audio=dict(frames=w.getnframes(),rate=w.getframerate(),channels=w.getnchannels())
    trace=out/"flow.tsv"
    if not trace.is_file():raise ValueError("missing complete trace; use instrumented Qwen CLI")
    if sources!=source_hashes(models):raise ValueError("GGUF changed during experiment")
    write_new(out/"run.json",dict(schema="roots.element-flow.qwen/1",status="complete",voice=args.voice,
        language=args.language,sources=sources,qwen_revision=QWEN,ggml_revision=GGML,
        executable_sha256=digest(exe),reference_sha256=references,text_sha256=hashlib.sha256(text).hexdigest(),
        seed=args.seed,greedy=args.greedy,max_new=args.max_new,trace_sha256=digest(trace),
        audio_sha256=digest(out/"speech.wav"),audio=audio))
    print("Recorded",out)

def read_run(path, sources, inv):
    meta=json.loads((path/"run.json").read_text(encoding="utf-8"))
    trace=path/"flow.tsv"
    if (meta["schema"]!="roots.element-flow.qwen/1" or meta["status"]!="complete" or
        meta["sources"]!=sources or digest(trace)!=meta["trace_sha256"]):
        raise ValueError("trace or model identity mismatch")
    # Average energy within each observed tensor, per stage execution. Lookup
    # rows have separate evidence; coalesce into aligned 32-row piece addresses.
    peaks={};seen=set();deps=set();flow_steps={}
    with trace.open(encoding="utf-8",newline="") as f:
        for row in csv.DictReader(f,delimiter="\t"):
            role,name=row["source"],row["tensor"];t=inv[role][name]
            offset,rb,rows,first,count=(int(row[k]) for k in ("offset","row_bytes","rows","first","count"))
            if offset!=t["offset"] or rows!=t["rows"] or rb<=0 or not 0<=first<rows or not 0<count<=rows-first:
                raise ValueError("trace geometry mismatch")
            if t["row_bytes"] is None:continue
            if rb!=t["row_bytes"]:raise ValueError("trace row size mismatch")
            kind=row["kind"]
            if kind=="dependency":
                if first!=0 or count!=rows:raise ValueError("bad dependency")
                deps.add((role,name));continue
            if kind not in ("flow","lookup"):raise ValueError("unknown evidence")
            score=float(row["share"]);rms=float(row["rms"])
            if not math.isfinite(score) or not 0<=score<=1.0000001 or not math.isfinite(rms) or rms<0:
                raise ValueError("invalid flow score")
            if kind=="flow" and (first%BLOCK or count!=min(BLOCK,rows-first)):
                raise ValueError("unexpected flow block geometry")
            if kind=="lookup" and count!=1:raise ValueError("invalid lookup")
            key=(role,name,first//BLOCK*BLOCK)
            # Peak within request avoids long speech dominating. It is a
            # within-projection proxy; not a cross-layer causal ranking.
            peaks[key]=max(peaks.get(key,0),min(score,1))
            seen.add(key)
    if not peaks:raise ValueError("trace has no supported flow or lookup observations")
    return meta,peaks,deps

def hash_range(path, offset, length, output=None):
    h=hashlib.sha256()
    with path.open("rb") as f:
        f.seek(offset)
        while length:
            data=f.read(min(CHUNK,length))
            if not data:raise IOError("truncated source range")
            h.update(data)
            if output:output.write(data)
            length-=len(data)
    return h.hexdigest()

def compile_axon(args):
    if args.budget<1 or not args.target or len(args.peer)>8:raise ValueError("invalid subscription limits")
    if any(not HEX.fullmatch(x) for x in args.peer):raise ValueError("peer must be a certificate SHA-256 pin")
    models=files(args);sources=source_hashes(models);inv={k:inventory(p) for k,p in models.items()}
    all_runs=[read_run(p,sources,inv) for p in args.target+args.control]
    targets=all_runs[:len(args.target)];controls=all_runs[len(args.target):]
    label={(m["voice"],m["language"]) for m,_,_ in targets}
    if len(label)!=1:raise ValueError("one voice/language per target set")
    identity={(m["executable_sha256"],m["qwen_revision"],m["ggml_revision"],m["greedy"],m["max_new"]) for m,_,_ in all_runs}
    if len(identity)!=1:raise ValueError("mixed engine/settings; compare separate profiles")
    if len({m["trace_sha256"] for m,_,_ in all_runs})!=len(all_runs):
        raise ValueError("duplicate traces are not independent evidence")
    scores={}
    for key in {k for _,s,_ in targets for k in s}:
        mean=sum(s.get(key,0) for _,s,_ in targets)/len(targets)
        control=sum(s.get(key,0) for _,s,_ in controls)/len(controls) if controls else None
        # Prefer target-active regions; contrast is exploratory, not a causal verdict.
        priority=mean if control is None else max(0,mean-control)
        if priority>0:scores[key]=(priority,mean,control)
    voice,language=next(iter(label))
    pieces=[];total=0
    # Under budget, choose disjoint blocks of physical rows. No omitted dependency
    # is declared unnecessary. Hash addresses are independent of peer locations.
    for (role,name,first),(priority,mean,control) in sorted(scores.items(),key=lambda p:(-p[1][0],p[0])):
        t=inv[role][name];count=min(BLOCK,t["rows"]-first)
        length=t["row_bytes"]*count;offset=t["offset"]+first*t["row_bytes"]
        if total+length>args.budget:continue
        sha=hash_range(models[role],offset,length)
        pieces.append(dict(source=role,tensor=name,first_row=first,row_count=count,type_id=t["type_id"],
                           dims=t["dims"],offset=offset,bytes=length,sha256=sha,
                           uri="roots://sha256/"+sha,element_flow=mean,control_flow=control,priority=priority))
        total+=length
    if not pieces:raise ValueError("no positive candidates fit budget")
    # Existing .axon v1 remains a topic/peer policy; this is an experimental
    # separate profile. Knowledge demand is supplied by the host later.
    mass=sum(p["priority"] for p in pieces)
    for p in pieces:p["element_share"]=p.pop("priority")/mass
    deps=set().union(*(d for _,_,d in targets))
    if sources!=source_hashes(models):raise ValueError("source changed during compilation")
    write_new(args.out,dict(type="roots.axon",version=2,profile="weight-flow-experimental",
        state="unvalidated",voice=voice,language=language,peers=sorted(set(args.peer)),
        model_sources=sources,engine_sha256=next(iter(identity))[0],
        target_requests=len(targets),control_requests=len(controls),budget_bytes=args.budget,
        selected_bytes=total,requires_dense_model=True,
        allocation=dict(knowledge_weight=0.5,element_weight=0.5,knowledge_demand=None),
        observed_dependencies=[dict(source=r,tensor=n) for r,n in sorted(deps)],
        pieces=pieces,warning="Local proposal only; not a sparse model or automatic transfer authorization."))
    print("Subscription:",args.out,"candidate bytes:",total,"of",sum(v["bytes"] for v in sources.values()))

def materialize(args):
    """Explicit local CAS export only, no upload or peer discovery."""
    doc=json.loads(args.axon.read_text(encoding="utf-8"))
    if doc.get("type")!="roots.axon" or doc.get("version")!=2 or doc.get("profile")!="weight-flow-experimental":
        raise ValueError("unsupported descriptor")
    models=files(args);sources=source_hashes(models)
    if sources!=doc["model_sources"]:raise ValueError("model digest mismatch")
    inv={k:inventory(p) for k,p in models.items()}
    out=args.out.resolve();out.mkdir(parents=True,exist_ok=True)
    # Do not consume arbitrary paths/URIs from the descriptor.
    for p in doc["pieces"]:
        role=p["source"];t=inv[role][p["tensor"]]
        first,count=p["first_row"],p["row_count"]
        if (not HEX.fullmatch(p["sha256"]) or not t["row_bytes"] or not 0<=first<t["rows"] or
            first%BLOCK or count!=min(BLOCK,t["rows"]-first) or
            p["offset"]!=t["offset"]+first*t["row_bytes"] or p["bytes"]!=count*t["row_bytes"] or
            p["type_id"]!=t["type_id"] or p["dims"]!=t["dims"] or p["uri"]!="roots://sha256/"+p["sha256"]):
            raise ValueError("invalid piece descriptor")
        dest=out/p["sha256"]
        if dest.exists():
            if dest.is_symlink() or dest.stat().st_size!=p["bytes"] or digest(dest)!=p["sha256"]:
                raise ValueError("bad existing CAS piece")
            continue
        with tempfile.NamedTemporaryFile(dir=out,delete=False) as f:
            tmp=Path(f.name)
            try:actual=hash_range(models[role],p["offset"],p["bytes"],f)
            except BaseException:
                f.close();tmp.unlink(missing_ok=True);raise
        try:
            if actual!=p["sha256"]:raise ValueError("piece digest mismatch")
            os.replace(tmp,dest)
        finally:tmp.unlink(missing_ok=True)
    print("Verified local pieces:",out)

def main():
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest="command",required=True)
    for name,fn in (("run",run),("compile",compile_axon),("materialize",materialize)):
        a=sub.add_parser(name);a.set_defaults(fn=fn)
        a.add_argument("--talker",type=Path,required=True);a.add_argument("--codec",type=Path,required=True)
        a.add_argument("--out",type=Path,required=True)
        if name=="run":
            a.add_argument("--exe",type=Path,required=True);a.add_argument("--text-file",type=Path,required=True)
            a.add_argument("--voice",required=True);a.add_argument("--language",required=True)
            ref=a.add_mutually_exclusive_group(required=True)
            ref.add_argument("--ref-wav",type=Path);ref.add_argument("--ref-spk",type=Path)
            a.add_argument("--ref-rvq",type=Path);a.add_argument("--ref-text",type=Path)
            a.add_argument("--seed",type=int,default=42);a.add_argument("--greedy",action="store_true")
            a.add_argument("--max-new",type=int,default=512);a.add_argument("--timeout",type=int,default=900)
        elif name=="compile":
            a.add_argument("--target",type=Path,action="append",required=True)
            a.add_argument("--control",type=Path,action="append",default=[])
            a.add_argument("--budget",type=int,default=4*1024*1024)
            a.add_argument("--peer",action="append",default=[])
        else:a.add_argument("--axon",type=Path,required=True)
    a=p.parse_args();a.fn(a)

if __name__=="__main__":main()
