#pragma once
// Copyright (c) 2026 We Develop Software LLC. All rights reserved.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <map>
#include <ostream>
#include <stdexcept>
#include <string>
#include <tuple>
#include <vector>

namespace roots::wave {
// Physical GGUF rows (including complete quantization blocks). Source is a
// caller-assigned role, bound to a file SHA-256 by the experiment manifest.
struct Range {
    std::string source, tensor;
    uint64_t offset, row_bytes, rows;
    void check(uint64_t first, uint64_t count) const {
        if(source.empty() || tensor.empty() || source.find_first_of("\t\r\n")!=std::string::npos ||
           tensor.find_first_of("\t\r\n")!=std::string::npos || !row_bytes || !rows ||
           !count || first>=rows || count>rows-first ||
           rows>(std::numeric_limits<uint64_t>::max()-offset)/row_bytes)
            throw std::invalid_argument("invalid GGUF range");
    }
};
struct Element {
    Range range;
    uint64_t first, count;
    std::string kind; // flow, lookup, dependency
    double share=0, rms=0;
    uint64_t byte_offset() const {return range.offset+first*range.row_bytes;}
    uint64_t byte_count() const {return count*range.row_bytes;}
    std::string key() const {
        return range.source+":"+range.tensor+":"+std::to_string(first)+":"+std::to_string(count);
    }
};
struct Step {
    std::string stage;
    std::vector<Element> elements;
    // Excludes dependency-only observations; compare scores within a projection.
    std::vector<Element> ranked(size_t limit) const {
        auto out=elements;
        out.erase(std::remove_if(out.begin(),out.end(),[](const auto& e){return e.kind!="flow" && e.kind!="lookup";}),out.end());
        std::stable_sort(out.begin(),out.end(),[](const auto& a,const auto& b){
            return a.share==b.share ? a.key()<b.key() : a.share>b.share;
        });
        if(out.size()>limit) out.resize(limit);
        return out;
    }
};
class Request {
    uint64_t block_rows_;
    Step step_;
    using Key=std::tuple<std::string,std::string,uint64_t,uint64_t,std::string>;
    std::map<Key,Element> elements_;
    bool open_=false;
    void add(const Range& r,uint64_t first,uint64_t count,std::string kind,double share=0,double rms=0) {
        if(!open_) throw std::logic_error("begin a step first");
        r.check(first,count);
        Key key{r.source,r.tensor,first,count,kind};
        auto result=elements_.try_emplace(key,Element{r,first,count,kind,share,rms});
        auto& e=result.first->second;
        if(e.range.offset!=r.offset || e.range.row_bytes!=r.row_bytes || e.range.rows!=r.rows)
            throw std::invalid_argument("inconsistent tensor geometry");
        // Multiple uses of the same range in a step: retain strongest observed signal.
        e.share=std::max(e.share,share); e.rms=std::max(e.rms,rms);
    }
public:
    explicit Request(uint64_t block_rows=64):block_rows_(block_rows) {
        if(!block_rows_) throw std::invalid_argument("zero block_rows");
    }
    void begin(std::string stage) {
        if(open_ || stage.empty() || stage.find_first_of("\t\r\n")!=std::string::npos)
            throw std::logic_error("invalid step");
        open_=true; step_={std::move(stage),{}}; elements_.clear();
    }
    void dependency(const Range& r){add(r,0,r.rows,"dependency");}
    void lookup(const Range& r,uint64_t row){add(r,row,1,"lookup",1);}
    // For direct GGML MUL_MAT: output [output rows, positions].
    // For fused QKV, output_first identifies the original projection's slice.
    // Row energy is an attribution proxy, not causal sufficiency or individual-weight importance.
    void observe(const Range& r,const float* output,uint64_t width,uint64_t positions,uint64_t output_first=0) {
        r.check(0,r.rows);
        if(!output || !width || !positions || output_first>=width || r.rows>width-output_first ||
           width>std::numeric_limits<size_t>::max()/positions)
            throw std::invalid_argument("invalid activation geometry");
        std::vector<double> energy(r.rows,0); double total=0;
        for(uint64_t p=0;p<positions;++p) for(uint64_t i=0;i<width;++i) {
            double v=output[p*width+i];
            if(!std::isfinite(v)) throw std::invalid_argument("non-finite activation");
            double e=v*v; total+=e;
            if(i>=output_first && i-output_first<r.rows) energy[i-output_first]+=e;
        }
        for(uint64_t first=0;first<r.rows;) {
            uint64_t n=std::min(block_rows_,r.rows-first);
            double e=0; for(uint64_t i=first;i<first+n;++i)e+=energy[i];
            add(r,first,n,"flow",total>0?e/total:0,std::sqrt(e/(double(n)*positions)));
            first+=n;
        }
    }
    Step finish() {
        if(!open_) throw std::logic_error("no active step");
        for(auto& p:elements_)step_.elements.push_back(std::move(p.second));
        elements_.clear(); open_=false; return std::move(step_);
    }
};
// Model-independent lookahead API. Learn a next-step hot set conditioned on
// caller-supplied request class and previous stage + top observed element.
// Predictions are hints for non-blocking preparation ONLY, never permission
// to omit weights. Use a separate instance per model/quant/backend profile.
struct Hint { Element element; double probability; uint64_t examples; };
class Lookahead {
    struct Counts {uint64_t examples=0; std::map<std::string,std::pair<Element,uint64_t>> hits;};
    using Key=std::tuple<std::string,std::string,std::string>;
    std::map<Key,Counts> table_;
    size_t top_;
    static std::string lead(const Step& step) {
        auto r=step.ranked(1); return r.empty()?"":r.front().key();
    }
    void learn(const Key& key,const Step& next) {
        auto& c=table_[key]; ++c.examples;
        std::map<std::string,Element> unique;
        for(auto& e:next.ranked(top_)) if(e.share>0) unique.emplace(e.key(),e);
        for(auto& p:unique) {
            auto& h=c.hits[p.first]; h.first=p.second; ++h.second;
        }
    }
public:
    explicit Lookahead(size_t top=32):top_(top) {if(!top_)throw std::invalid_argument("zero top");}
    void train(const std::string& context,const Step& previous,const Step& next) {
        auto k=lead(previous);
        learn({context,previous.stage,""},next);
        if(!k.empty())learn({context,previous.stage,k},next);
    }
    std::vector<Hint> predict(const std::string& context,const Step& previous,uint64_t min_examples=3,size_t limit=32) const {
        auto it=table_.find({context,previous.stage,lead(previous)});
        if(it==table_.end() || it->second.examples<min_examples)
            it=table_.find({context,previous.stage,""});
        if(it==table_.end() || it->second.examples<min_examples)return {};
        std::vector<Hint> result;
        for(auto& p:it->second.hits)
            result.push_back({p.second.first,double(p.second.second)/it->second.examples,it->second.examples});
        std::sort(result.begin(),result.end(),[](const auto& a,const auto& b){
            return a.probability==b.probability?a.element.key()<b.element.key():a.probability>b.probability;
        });
        if(result.size()>limit)result.resize(limit);
        return result;
    }
};
inline void trace_header(std::ostream& out){
    out<<"step\tstage\tsource\ttensor\toffset\trow_bytes\trows\tfirst\tcount\tkind\tshare\trms\n";
}
inline void trace_step(std::ostream& out,uint64_t n,const Step& s){
    out.precision(17);
    for(const auto& e:s.elements)
        out<<n<<'\t'<<s.stage<<'\t'<<e.range.source<<'\t'<<e.range.tensor<<'\t'
           <<e.range.offset<<'\t'<<e.range.row_bytes<<'\t'<<e.range.rows<<'\t'
           <<e.first<<'\t'<<e.count<<'\t'<<e.kind<<'\t'<<e.share<<'\t'<<e.rms<<'\n';
    if(!out)throw std::runtime_error("trace write failed");
}
} // namespace roots::wave
