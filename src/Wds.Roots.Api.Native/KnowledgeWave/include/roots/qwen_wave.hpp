#pragma once
// Opt-in Qwen CLI research adapter. One synthesis per process. Not a concurrent server hook.
#include "knowledge_wave.hpp"
#include "ggml.h"
#include "ggml-alloc.h"
#include "ggml-backend.h"
#include <cstdlib>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <unordered_map>
#include <unordered_set>

namespace roots::qwen_wave {
using wave::Range;
struct Binding { Range range; uint64_t output_first; bool matrix; };
struct Tap { ggml_tensor* node; std::vector<Binding> bindings; };
struct Graph { std::string stage; std::vector<Range> dependencies; std::vector<Tap> taps; };
struct Session {
    wave::Request request{32};
    std::unordered_map<const ggml_tensor*,std::vector<Binding>> weights;
    std::unordered_map<ggml_cgraph*,Graph> graphs;
    std::filesystem::path path,tmp;
    std::ofstream trace;
    uint64_t step=0;
    bool failed=false;
    Session(){
        if(const char* p=std::getenv("ROOTS_WAVE_TRACE");p && *p){
            path=std::filesystem::u8path(p);tmp=path;tmp+=".tmp";
            if(std::filesystem::exists(path)||std::filesystem::exists(tmp))
                throw std::runtime_error("trace destination exists");
            trace.open(tmp);
            if(!trace)throw std::runtime_error("cannot open trace");
            wave::trace_header(trace);
        }
    }
    ~Session(){
        if(path.empty()||failed)return;
        try{
            trace.close();
            if(!trace)throw std::runtime_error("trace close failed");
            std::filesystem::rename(tmp,path);
        }catch(...){std::fprintf(stderr,"Roots Wave: trace publication failed\n");}
    }
    void finish(){wave::trace_step(trace,step++,request.finish());}
};
inline Session& session(){static Session s;return s;}
inline bool enabled(){return !session().path.empty();}
inline int source(const char* path){
    if(!enabled())return 0;
    const char* a=std::getenv("ROOTS_WAVE_TALKER");
    const char* b=std::getenv("ROOTS_WAVE_CODEC");
    if(a && std::string(path)==a)return 1;
    if(b && std::string(path)==b)return 2;
    throw std::runtime_error("unrecognized GGUF source in profiling process");
}
inline Range range(int id,const std::string& name,uint64_t offset,const ggml_tensor* original){
    if((id!=1 && id!=2)||!original)throw std::runtime_error("invalid source binding");
    const auto row=ggml_row_size(original->type,original->ne[0]);
    return {id==1?"talker":"codec",name,offset,row,ggml_nbytes(original)/row};
}
inline void bind(ggml_tensor* target,int id,const std::string& name,uint64_t offset,
                 const ggml_tensor* original,uint64_t first=0,bool matrix=true){
    if(!enabled())return;
    matrix=matrix && ggml_n_dims(original)==2;
    session().weights[target].push_back({range(id,name,offset,original),first,matrix});
}
inline void host(int id,const std::string& name,uint64_t offset,const ggml_tensor* original,int64_t row=-1){
    if(!enabled())return;
    auto& s=session();auto r=range(id,name,offset,original);
    s.request.begin("host.lookup");
    if(row>=0)s.request.lookup(r,uint64_t(row));else s.request.dependency(r);
    s.finish();
}
inline void walk(const ggml_tensor* t,std::unordered_set<const ggml_tensor*>& seen,Graph& g){
    if(!t||!seen.insert(t).second)return;
    auto it=session().weights.find(t);
    if(it!=session().weights.end())for(const auto& b:it->second)g.dependencies.push_back(b.range);
    for(auto* p:t->src)walk(p,seen,g);
    walk(t->view_src,seen,g);
}
inline void prepare(const char* stage,ggml_cgraph* graph){
    if(!enabled())return;
    Graph g;g.stage=stage;std::unordered_set<const ggml_tensor*> seen;
    // Retained activation budget PER graph. Coverage is intentionally partial.
    constexpr size_t budget=32u*1024u*1024u;size_t bytes=0;
    for(int i=0;i<ggml_graph_n_nodes(graph);++i){
        auto* t=ggml_graph_node(graph,i);walk(t,seen,g);
        if(t->op!=GGML_OP_MUL_MAT||t->type!=GGML_TYPE_F32||!ggml_is_contiguous(t)||
           !t->src[0]||ggml_n_dims(t)>2)continue;
        auto it=session().weights.find(t->src[0]);
        if(it==session().weights.end())continue;
        uint64_t width=0;bool exact=true;
        for(const auto& b:it->second){
            if(!b.matrix||b.output_first!=width){exact=false;break;}
            width+=b.range.rows;
        }
        const size_t n=ggml_nbytes(t);
        if(!exact||width!=uint64_t(t->ne[0])||n>budget-bytes)continue;
        // Mark before allocator runs; do not read recycled intermediate memory.
        ggml_set_output(t);bytes+=n;g.taps.push_back({t,it->second});
    }
    session().graphs[graph]=std::move(g);
}
inline void after(ggml_cgraph* graph,ggml_status status,bool persistent){
    if(!enabled())return;
    auto& s=session();auto it=s.graphs.find(graph);
    if(status!=GGML_STATUS_SUCCESS||it==s.graphs.end()){s.failed=true;return;}
    s.request.begin(it->second.stage);
    for(const auto& r:it->second.dependencies)s.request.dependency(r);
    for(const auto& tap:it->second.taps){
        auto* t=tap.node;
        if(!t->buffer||!t->data){s.failed=true;continue;}
        std::vector<float> data(ggml_nelements(t));
        ggml_backend_tensor_get(t,data.data(),0,data.size()*sizeof(float));
        for(const auto& b:tap.bindings)
            s.request.observe(b.range,data.data(),t->ne[0],ggml_nelements(t)/t->ne[0],b.output_first);
    }
    s.finish();if(!persistent)s.graphs.erase(it);
}
inline bool sched_alloc(const char* stage,ggml_backend_sched_t s,ggml_cgraph* g){
    prepare(stage,g);return ggml_backend_sched_alloc_graph(s,g);
}
inline bool direct_alloc(const char* stage,ggml_gallocr_t a,ggml_cgraph* g){
    prepare(stage,g);return ggml_gallocr_alloc_graph(a,g);
}
inline ggml_status sched_compute(ggml_backend_sched_t s,ggml_cgraph* g){
    auto status=ggml_backend_sched_graph_compute(s,g);
    if(enabled())ggml_backend_sched_synchronize(s);
    after(g,status,false);return status;
}
inline ggml_status direct_compute(ggml_backend_t b,ggml_cgraph* g){
    auto status=ggml_backend_graph_compute(b,g);
    if(enabled())ggml_backend_synchronize(b);
    after(g,status,true);return status;
}
} // namespace roots::qwen_wave
