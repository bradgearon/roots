"""Desktop experiment. Run through atlas.cmd; downloads only selected SAE weights."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
from collections import defaultdict
import atlas_core as core

REF="google/gemma-3-270m-it"
SCOPE="google/gemma-scope-2-270m-it"
GGUF="ggml-org/gemma-3-270m-it-GGUF"
GGUF_FILE="gemma-3-270m-it-Q8_0.gguf"
LAYERS=(5,9,12,15)
# Deliberately separate fixed held-out requests from adaptive training.
TRAIN={
 "english":["Correct the grammar: she walk to school yesterday.","Explain the difference between borrow and lend.","Rewrite 'the rain fell softly' in ordinary English."],
 "understanding":["Why does a shadow change length during the day?","Explain how a cause differs from a correlation.","Describe why a shared rule can help people cooperate."],
 "poetry":["Resolve the image: the ocean remembers the shore.","Write a metaphor about a root listening for rain.","Explain the tension in 'the flame forgives the wood'."],
 "engineering":["Describe how a queue handles concurrent requests.","Explain the purpose of a checksum in a data protocol.","Compare a cache and persistent storage."],
 "mathematics":["Explain why two negative numbers multiply to a positive.","Find the next number: 2,4,8,16.","Explain the difference between mean and median."],
 "control":["Repeat the letters q z p k in reverse order.","Return exactly the word table.","List three random colors without explanation."]}
TEST={
 "english":["Choose between affect and effect in a sentence.","Make this sentence grammatical: they is ready."],
 "understanding":["Why does ice float in water?","Explain how two different explanations can both fit an observation."],
 "poetry":["Resolve the image: dawn heals the sky.","Write a short metaphor about silence carrying light."],
 "engineering":["Explain why a network request needs a deadline.","Describe how a database transaction protects consistency."],
 "mathematics":["Explain why a fraction can be larger than one.","What is the next term of 3,6,12,24?"],
 "control":["Output the letters r t m only.","Name an arbitrary fruit."]}

def save(path,value):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    tmp=path.with_suffix(path.suffix+".tmp")
    tmp.write_text(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False),encoding="utf-8")
    os.replace(tmp,path)

def sha(path):
    with open(path,"rb") as f:
        h=hashlib.sha256()
        for b in iter(lambda:f.read(1024*1024),b""):h.update(b)
        return h.hexdigest()

def inventory(path):
    from gguf import GGUFReader
    r=GGUFReader(str(path))
    tensors={}
    for t in r.tensors:
        offset,size=int(t.data_offset),int(t.n_bytes)
        if offset<0 or size<=0 or offset+size>path.stat().st_size or t.name in tensors:
            raise ValueError("Invalid tensor directory")
        tensors[t.name]={"offset":offset,"size":size,"shape":[int(x) for x in t.shape],"type":t.tensor_type.name}
    # Structural check against known Gemma 3 270M architecture.
    for l in range(18):
        for k in ("ffn_gate","ffn_up"):
            t=tensors.get(f"blk.{l}.{k}.weight")
            if not t or t["shape"]!=[640,2048]:
                raise ValueError("Expected Gemma 3 270M FFN shape [640,2048]")
    if "blk.18.ffn_up.weight" in tensors:
        raise ValueError("Unexpected layer count")
    return tensors

class Observer:
    def __init__(self,args,manifest=None):
        import torch
        from transformers import AutoModelForCausalLM,AutoTokenizer
        from huggingface_hub import HfApi,hf_hub_download
        from safetensors.torch import load_file
        import transformers
        self.torch=torch
        self.device=args.device if args.device!="auto" else ("cuda" if torch.cuda.is_available() else "cpu")
        torch.manual_seed(701)
        self.ref_revision=manifest["reference_revision"] if manifest else HfApi().model_info(REF).sha
        self.scope_revision=manifest["scope_revision"] if manifest else HfApi().model_info(SCOPE).sha
        self.layers=tuple(manifest["layers"]) if manifest else tuple(args.layers)
        self.tokenizer=AutoTokenizer.from_pretrained(REF,revision=self.ref_revision)
        self.model=AutoModelForCausalLM.from_pretrained(REF,revision=self.ref_revision,torch_dtype=torch.float32,attn_implementation="eager").to(self.device).eval()
        self.blocks=self.model.model.layers
        if len(self.blocks)!=18 or self.model.config.hidden_size!=640 or self.model.config.intermediate_size!=2048:
            raise ValueError("Unexpected reference architecture")
        self.sae={}
        self.sae_hash={}
        for l in self.layers:
            folder=f"resid_post/layer_{l}_width_16k_l0_medium"
            config_path=hf_hub_download(SCOPE,folder+"/config.json",revision=self.scope_revision)
            cfg=json.loads(Path(config_path).read_text())
            if cfg.get("hf_hook_point_in")!=f"model.layers.{l}.output" or cfg.get("model_name")!=REF or cfg.get("architecture")!="jump_relu":
                raise ValueError("Scope site/model mismatch")
            path=hf_hub_download(SCOPE,folder+"/params.safetensors",revision=self.scope_revision)
            digest=sha(path)
            if manifest and manifest["sae_sha256"][str(l)]!=digest:raise ValueError("SAE hash changed")
            p=load_file(path)
            needed=("w_enc","b_enc","threshold")
            if not all(k in p for k in needed):raise ValueError(f"Unexpected SAE keys: {list(p)}")
            w,b,t=(p[k].float().to(self.device) for k in needed)
            if tuple(w.shape)!=(640,16384) or b.numel()!=16384 or t.numel()!=16384:
                raise ValueError("SAE encoder dimensions mismatch")
            self.sae[l]=(w,b.reshape(-1),t.reshape(-1))
            self.sae_hash[str(l)]=digest
        self.versions={"torch":str(torch.__version__),"transformers":transformers.__version__}
    def tokens(self,text):
        # Use matching instruct template, observe input before generation.
        prompt=self.tokenizer.apply_chat_template([{"role":"user","content":text}],tokenize=False,add_generation_prompt=True)
        return self.tokenizer(prompt,return_tensors="pt",add_special_tokens=False,truncation=True,max_length=256).to(self.device)
    def observe(self,text):
        t=self.torch; features={}; groups={}; handles=[]; residual={}; ffn={}
        # Identify actual user-content tokens rather than the chat-template suffix.
        prompt=self.tokenizer.apply_chat_template([{"role":"user","content":text}],tokenize=False,add_generation_prompt=True)
        start=prompt.rfind(text)
        if start<0:raise ValueError("Could not identify request span in template")
        encoded=self.tokenizer(prompt,return_tensors="pt",add_special_tokens=False,return_offsets_mapping=True)
        offsets=encoded.pop("offset_mapping")[0].tolist()
        if len(offsets)>256:raise ValueError("Probe exceeds the 256-token observation budget")
        positions=[i for i,(a,b) in enumerate(offsets) if b>a and a<start+len(text) and b>start]
        if not positions:raise ValueError("No user-content tokens")
        if len(positions)>32:
            positions=[positions[i*(len(positions)-1)//31] for i in range(32)]
        pos=t.tensor(positions,device=self.device,dtype=t.long)
        def layer_hook(l):
            def hook(module,inp,out):
                x=out[0] if isinstance(out,tuple) else out
                residual[l]=x[0].index_select(0,pos).float().clone()
            return hook
        def ffn_hook(l):
            def hook(module,inp):
                x=inp[0][0].index_select(0,pos).float()
                if x.shape[-1]!=2048:raise ValueError("FFN channel mismatch")
                ffn[l]=x.square().reshape(x.shape[0],64,32).sum(dim=2)
            return hook
        for l,block in enumerate(self.blocks):
            handles.append(block.mlp.down_proj.register_forward_pre_hook(ffn_hook(l)))
            if l in self.sae:handles.append(block.register_forward_hook(layer_hook(l)))
        try:
            with t.inference_mode():
                output=self.model.model(**encoded.to(self.device),use_cache=False,output_attentions=True,return_dict=True)
                attentions=output.attentions
                if attentions is None or len(attentions)!=len(self.blocks):
                    raise RuntimeError("Eager attention weights unavailable")
                for l in range(len(self.blocks)):
                    attn=attentions[l]
                    if attn is None or attn.ndim!=4 or attn.shape[-1]!=len(offsets):
                        raise RuntimeError("Unexpected attention shape")
                    a=attn[0,:,-1,:].float().mean(dim=0).index_select(0,pos)
                    total=a.sum()
                    # Attention is a routing signal, not causal importance.
                    uniform=t.ones_like(a)/len(positions)
                    weights=.5*uniform+.5*(a/total if float(total)>0 else uniform)
                    energy=(ffn[l]*weights[:,None]).sum(dim=0)
                    groups.update(core.normalized({f"{l}:{i}":float(v) for i,v in enumerate(energy.tolist())}))
                    if l in residual:
                        w,b,threshold=self.sae[l]
                        z=residual[l]@w+b
                        active=t.where(z>threshold,z,t.zeros_like(z)).clamp_min(0)
                        pooled=(active*weights[:,None]).sum(dim=0)
                        vals,ids=t.topk(pooled,64)
                        features.update(core.normalized({f"{l}:{int(i)}":float(v) for i,v in zip(ids.tolist(),vals.tolist()) if v>0}))
        finally:
            for h in handles:h.remove()
        if not features or not groups:raise RuntimeError("No feature/group observations")
        return {"features":core.normalized(features),"groups":core.normalized(groups)}
    def micro(self,summary,mode):
        """Optional local controller; no tool execution, no test edits."""
        t=self.torch
        instruction=("You are a small experiment supervisor. Return one JSON object only. "
                     "Choose a category from english, understanding, poetry, engineering, mathematics, control. "
                     "Provide keys category, question, speech. Question is a short new public synthetic test. "
                     "Speech is one short cautious explanation of the measured results. "
                     "Do not claim causality or correctness from activation coverage. "
                     f"Mode: {mode}. Measurements: {json.dumps(summary,ensure_ascii=True)}")
        with t.inference_mode():
            x=self.tokens(instruction)
            output=self.model.generate(**x,max_new_tokens=128,do_sample=False,pad_token_id=self.tokenizer.eos_token_id)
        text=self.tokenizer.decode(output[0,x["input_ids"].shape[1]:],skip_special_tokens=True)
        start,end=text.find("{"),text.rfind("}")
        try:
            obj=json.loads(text[start:end+1])
            cat,q,s=obj["category"],obj["question"],obj["speech"]
            if cat not in TRAIN or not isinstance(q,str) or not isinstance(s,str) or not 5<=len(q)<=240 or not 1<=len(s)<=400:
                raise ValueError()
            if any(c in q+s for c in "<>\x00") or any(ord(c)<32 for c in q+s):raise ValueError()
            return {"category":cat,"question":q,"speech":s,"source":"local Gemma proposal"}
        except (KeyError,TypeError,ValueError):
            return None

def sample(observer,category,text,tag):
    result=observer.observe(text)
    return {"id":tag,"category":category,**result}

def resolve_gguf(args,manifest=None):
    from huggingface_hub import hf_hub_download
    path=Path(args.gguf) if args.gguf else Path(hf_hub_download(GGUF,GGUF_FILE,revision=manifest.get("gguf_revision","main") if manifest else "main"))
    if not path.is_file():raise FileNotFoundError(path)
    return path

def read_ranges(path,selected,out):
    # Byte-for-byte extraction, not a standalone runnable model.
    out.mkdir(parents=True,exist_ok=True)
    output=out/"ranges.bin"
    index=[];total=0
    with path.open("rb") as source,output.open("wb") as dest:
        for element in selected:
            for r in element["ranges"]:
                source.seek(r["offset"]);data=source.read(r["length"])
                if len(data)!=r["length"]:raise IOError("Short model read")
                index.append({**r,"group":element["group"],"output_offset":total,"sha256":hashlib.sha256(data).hexdigest()})
                dest.write(data);total+=len(data)
    save(out/"ranges.json",{"bytes":total,"ranges":index,"source_sha256":sha(path)})
    return total

def build(args):
    path=resolve_gguf(args);tensors=inventory(path);digest=sha(path)
    obs=Observer(args)
    rows=[];used=set()
    for cat,texts in TRAIN.items():
        for i,text in enumerate(texts):
            print("Observe",cat,i+1,flush=True)
            rows.append(sample(obs,cat,text,f"train-{cat}-{i}"));used.add(text.casefold())
    # Adaptive training never receives held-out examples. Fixed test set is frozen.
    frozen={q.casefold() for vals in TEST.values() for q in vals}
    controller=[]
    for round in range(args.rounds if not args.no_micro else 0):
        index=core.learn(rows)
        weakest=min(TRAIN,key=lambda c:sum(p["category"]==c for p in rows))
        summary={"observations":len(rows),"features":len(index["freq"]),"least_sampled_category":weakest,
                 "rare_features":sum(n==1 for n in index["freq"].values())}
        proposal=obs.micro(summary,"choose next training probe")
        if not proposal or proposal["question"].casefold() in used|frozen:
            print("Controller abstained; using fixed probes only for this round.",flush=True);continue
        print("Micro-cognition:",proposal["speech"],"\nNext question:",proposal["question"],flush=True)
        used.add(proposal["question"].casefold())
        try:rows.append(sample(obs,proposal["category"],proposal["question"],f"adaptive-{round}"))
        except ValueError as e:
            print("Controller probe rejected:",str(e),flush=True);continue
        controller.append(proposal)
    atlas=core.learn(rows)
    tests=[sample(obs,cat,text,f"test-{cat}-{i}") for cat,texts in TEST.items() for i,text in enumerate(texts)]
    metrics=core.evaluate(tests,atlas,tensors,args.budget*1024*1024)
    manifest={"format":"roots.cognitive-atlas.v1","reference":REF,"reference_revision":obs.ref_revision,
              "scope":SCOPE,"scope_revision":obs.scope_revision,"layers":list(obs.layers),"sae_sha256":obs.sae_hash,
              "gguf_sha256":digest,"gguf_file":str(path.resolve()),"versions":obs.versions,
              "pooling":"user-token span, up to 32 rows, half uniform and half final-position head-mean attention",
              "compatibility":"structural alignment only; reference FP32 activations not validated against quantized runtime"}
    save(args.out/"atlas.json",{"manifest":manifest,"tensors":tensors,"graph":atlas,"training":rows,"evaluation":metrics,"controller":controller})
    print(json.dumps(metrics["mean"],indent=2),flush=True)
    print("Saved",args.out/"atlas.json")
    if not args.no_micro:
        proposal=obs.micro(metrics["mean"],"explain held-out retrieval coverage; suggest future independent experiment")
        if proposal:
            print("Micro-cognition assessment (not a test result):",proposal["speech"])
            print("Suggested future question:",proposal["question"])

def query(args):
    file=args.out/"atlas.json";data=json.loads(file.read_text(encoding="utf-8"))
    manifest=data["manifest"]
    if manifest.get("pooling")!="user-token span, up to 32 rows, half uniform and half final-position head-mean attention":raise ValueError("Rebuild Atlas with the current attention pooling method")
    path=Path(args.gguf) if args.gguf else Path(manifest["gguf_file"])
    if sha(path)!=manifest["gguf_sha256"]:raise ValueError("GGUF identity differs from atlas")
    tensors=inventory(path)
    if tensors!=data["tensors"]:raise ValueError("GGUF tensor directory differs")
    observer=Observer(args,manifest)
    observed=observer.observe(args.request)
    scores,activated=core.rank(observed["features"],data["graph"])
    selected,used=core.plan(scores,tensors,args.budget*1024*1024)
    actual=core.coverage(selected,observed["groups"])
    summary={"bytes":used,"groups":len(selected),"observed_energy_coverage":actual,
             "known_feature_mass":sum(v for f,v in observed["features"].items() if f in data["graph"]["freq"])}
    # Request text remains in memory, not in persisted output.
    save(args.out/"last-request.json",{"summary":summary,"feature_wave":activated,"plan":selected,"model_sha256":manifest["gguf_sha256"]})
    if args.extract:read_ranges(path,selected,args.out/"retrieved")
    print(json.dumps(summary,indent=2))
    for item in selected[:12]:print(item["group"],f'{item["score"]:.6f}',item["ranges"])
    if not args.no_micro:
        proposal=observer.micro(summary,"explain retrieval and suggest a discriminating next probe")
        if proposal:print("Micro-cognition:",proposal["speech"],"\nNext question:",proposal["question"])

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command",choices=["run","query"])
    parser.add_argument("--gguf",help="Existing Gemma 3 270M GGUF; run downloads Q8 if omitted")
    parser.add_argument("--out",type=Path,default=Path(".roots-atlas"))
    parser.add_argument("--device",choices=["auto","cpu","cuda"],default="auto")
    parser.add_argument("--layers",type=int,nargs="+",choices=LAYERS,default=[5,9,12,15])
    parser.add_argument("--budget",type=int,default=4,help="MiB of original gate/up rows")
    parser.add_argument("--rounds",type=int,default=3)
    parser.add_argument("--no-micro",action="store_true")
    parser.add_argument("--request",default="")
    parser.add_argument("--extract",action="store_true")
    args=parser.parse_args()
    if not 1<=args.budget<=64 or not 0<=args.rounds<=8 or len(set(args.layers))!=len(args.layers):
        parser.error("Budget 1-64 MiB, rounds 0-8, distinct layers")
    if args.command=="query" and not 1<=len(args.request)<=2000:parser.error("query requires --request (1-2000 chars)")
    if args.command=="run":build(args)
    else:query(args)

if __name__=="__main__":main()
