#!/usr/bin/env python3
"""Create a separate instrumented Qwen checkout, leaving upstream source untouched."""
import argparse
from pathlib import Path
import shutil
import subprocess

QWEN = "2edb35f87b709a070b2d5379f77e5c52a1daa585"
GGML = "ef4555b7aede4467a5de803b103615e0a5f30dd8"
API = Path(__file__).resolve().parents[2] / "src/Wds.Roots.Api.Native/KnowledgeWave/include/roots"
NS = "roots::qwen_wave::"

def git(*args):
    return subprocess.check_output(["git", *map(str,args)], text=True).strip()

def replace_once(s, old, new):
    if s.count(old) != 1:
        raise RuntimeError("Qwen hook no longer matches pinned source: " + old[:120])
    return s.replace(old,new,1)

def patch_weights(s):
    s=replace_once(s,'#include "gguf.h"','#include "gguf.h"\n#include "roots/qwen_wave.hpp"')
    s=replace_once(s,"struct GGUFModel {","struct GGUFModel {\n    int wave_source;")
    s=replace_once(s,"static bool gf_load(GGUFModel * gf, const char * path) {\n    *gf = {};",
                     "static bool gf_load(GGUFModel * gf, const char * path) {\n    *gf = {};\n    gf->wave_source = "+NS+"source(path);")
    s=replace_once(s,"wctx->pending.push_back({ tensor, data, nbytes, 0 });",
        NS+"bind(tensor,gf.wave_source,name,gf.data_offset+offset,src);\n    wctx->pending.push_back({ tensor, data, nbytes, 0 });")
    s=replace_once(s,"wctx->pending.push_back({ tensor, data, n * sizeof(float), 0 });",
        NS+"bind(tensor,gf.wave_source,name,gf.data_offset+offset,src);\n    wctx->pending.push_back({ tensor, data, n * sizeof(float), 0 });")
    s=replace_once(s,"wctx->pending.push_back({ tensor, (const void *) data, n * sizeof(ggml_fp16_t), 0 });",
        NS+"bind(tensor,gf.wave_source,name,gf.data_offset+raw_off,src,0,false);\n    wctx->pending.push_back({ tensor, (const void *) data, n * sizeof(ggml_fp16_t), 0 });")
    for name, first in [("q","0"),("k","q_src->ne[1]"),("v","q_src->ne[1]+k_src->ne[1]")]:
        old="wctx->pending.push_back({ fused, get_data("+name+"_name)"
        new=NS+"bind(fused,gf.wave_source,"+name+"_name,gf.data_offset+gguf_get_tensor_offset(gf.gguf,gguf_find_tensor(gf.gguf,"+name+"_name.c_str())),"+name+"_src,"+first+");\n    "+old
        s=replace_once(s,old,new)
    old="size_t offset = gguf_get_tensor_offset(gf.gguf, idx);\n    return gf.mapping + gf.data_offset + offset;"
    s=replace_once(s,old,"size_t offset = gguf_get_tensor_offset(gf.gguf, idx);\n    "+NS+"host(gf.wave_source,name,gf.data_offset+offset,ggml_get_tensor(gf.meta,name));\n    return gf.mapping + gf.data_offset + offset;")
    return s

HOOKS={
 "talker-forward.h":(["talker.prefill"],["talker.decode"]),
 "code-predictor-forward.h":([],["predictor.decode"]),
 "pipeline-codec.cpp":(["codec.decode","codec.encode"],["codec.stream"]),
 "speaker-encoder-extract.h":(["speaker.encode"],[]),
 "prompt-builder.h":(["prompt.project","prompt.reference"],[]),
}
def patch_graphs(s, sched, direct):
    for old,new,labels in [
      ("ggml_backend_sched_alloc_graph(", "sched_alloc(",sched),
      ("ggml_gallocr_alloc_graph(", "direct_alloc(",direct)]:
        if s.count(old)!=len(labels):
            raise RuntimeError("allocation hook count mismatch: "+old)
        for label in labels:
            s=s.replace(old,NS+new+'"'+label+'",',1)
    for old,new,count in [
      ("ggml_backend_sched_graph_compute(","sched_compute(",len(sched)),
      ("ggml_backend_graph_compute(","direct_compute(",len(direct)))]:
        if s.count(old)!=count:
            raise RuntimeError("compute hook count mismatch: "+old)
        s=s.replace(old,NS+new)
    return s

def patch_host_rows(s):
    old="const void * row       = base + (size_t) row_id * row_bytes;"
    new=old+"\n    "+NS+"host(gf.wave_source,tensor_name,gf.data_offset+gguf_get_tensor_offset(gf.gguf,gguf_find_tensor(gf.gguf,tensor_name)),src,row_id);"
    return replace_once(s,old,new)

def stage(source,dest):
    source=source.resolve();dest=dest.resolve()
    if dest.exists():raise ValueError("stage destination must be new")
    for path,revision in [(source,QWEN),(source/"ggml",GGML)]:
        git("-C",path,"cat-file","-e",revision+"^{commit}")
    subprocess.run(["git","clone","--local","--no-hardlinks","--no-checkout",str(source),str(dest)],check=True)
    git("-C",dest,"checkout","--detach",QWEN)
    subprocess.run(["git","clone","--local","--no-hardlinks","--no-checkout",str(source/"ggml"),str(dest/"ggml")],check=True)
    git("-C",dest/"ggml","checkout","--detach",GGML)
    src=dest/"src"
    shutil.copytree(API,src/"roots")
    weight=src/"gguf-weights.h";weight.write_text(patch_weights(weight.read_text(encoding="utf-8")),encoding="utf-8")
    for name,(sched,direct) in HOOKS.items():
        path=src/name;s=path.read_text(encoding="utf-8")
        s=patch_graphs(s,sched,direct)
        if name=="prompt-builder.h":s=patch_host_rows(s)
        # Explicit include; no reliance on header include order.
        path.write_text('#include "roots/qwen_wave.hpp"\n'+s,encoding="utf-8")
    path=src/"pipeline-tts.cpp"
    path.write_text(patch_host_rows(path.read_text(encoding="utf-8")),encoding="utf-8")
    print("Staged",dest)

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("source",type=Path);p.add_argument("destination",type=Path)
    a=p.parse_args();stage(a.source,a.destination)
