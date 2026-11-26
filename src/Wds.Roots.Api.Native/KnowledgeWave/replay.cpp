// Model-free evaluation of native Lookahead on completed Qwen flow traces.
// Training traces and held-out traces are distinct files; no learning during evaluation.
#include "roots/knowledge_wave.hpp"
#include <fstream>
#include <iostream>
#include <sstream>
#include <set>
#include <filesystem>

using namespace roots::wave;
static std::vector<std::string> fields(const std::string& s){
    std::vector<std::string> v;std::istringstream in(s);std::string f;
    while(std::getline(in,f,'\t'))v.push_back(f);
    return v;
}
static std::vector<Step> load(const std::string& path){
    std::ifstream f(std::filesystem::u8path(path));
    if(!f)throw std::runtime_error("cannot open trace "+path);
    std::string line;std::getline(f,line);
    if(!line.empty()&&line.back()=='\r')line.pop_back();
    if(line!="step\tstage\tsource\ttensor\toffset\trow_bytes\trows\tfirst\tcount\tkind\tshare\trms")
        throw std::runtime_error("unsupported trace");
    std::vector<Step> out;uint64_t previous=0;
    while(std::getline(f,line)){
        if(!line.empty()&&line.back()=='\r')line.pop_back();
        auto v=fields(line);if(v.size()!=12)throw std::runtime_error("bad trace row");
        auto step=std::stoull(v[0]);
        if(out.empty()||step!=previous){
            if(!out.empty()&&step<=previous)throw std::runtime_error("unordered trace");
            out.push_back({v[1],{}});previous=step;
        }else if(out.back().stage!=v[1])throw std::runtime_error("inconsistent stage");
        Element e{{v[2],v[3],std::stoull(v[4]),std::stoull(v[5]),std::stoull(v[6])},
                   std::stoull(v[7]),std::stoull(v[8]),v[9],std::stod(v[10]),std::stod(v[11])};
        e.range.check(e.first,e.count);
        if(!std::isfinite(e.share)||e.share<0||e.share>1.0000001||!std::isfinite(e.rms)||e.rms<0||
           (e.kind!="flow"&&e.kind!="lookup"&&e.kind!="dependency"))
            throw std::runtime_error("invalid trace evidence");
        out.back().elements.push_back(std::move(e));
    }
    if(out.size()<2)throw std::runtime_error("need at least two observed steps");
    return out;
}
int main(int argc,char** argv){
    if(argc<5){
        std::cerr<<"roots-wave-replay CONTEXT TRAIN.tsv [TRAIN.tsv...] --holdout TEST.tsv [TEST.tsv...]\n";return 2;
    }
    try{
        Lookahead predictor(32);std::set<std::filesystem::path> paths;
        std::vector<std::vector<Step>> holdout;bool testing=false;size_t trained=0;
        for(int i=2;i<argc;++i){
            if(std::string(argv[i])=="--holdout"){if(testing)throw std::runtime_error("duplicate separator");testing=true;continue;}
            auto path=std::filesystem::canonical(std::filesystem::u8path(argv[i]));
            if(!paths.insert(path).second)throw std::runtime_error("duplicate file");
            auto steps=load(argv[i]);
            if(testing)holdout.push_back(std::move(steps));
            else{for(size_t j=1;j<steps.size();++j){predictor.train(argv[1],steps[j-1],steps[j]);++trained;}}
        }
        if(!trained||holdout.empty())throw std::runtime_error("train and holdout required");
        constexpr uint64_t budget=4u*1024u*1024u;
        size_t evaluated=0,covered=0,actual=0,hits=0,predicted=0;uint64_t bytes=0,wasted=0;
        for(const auto& steps:holdout)for(size_t j=1;j<steps.size();++j){
            std::set<std::string> target;
            for(const auto& e:steps[j].ranked(32))if(e.share>0)target.insert(e.key());
            if(target.empty())continue;
            ++evaluated;actual+=target.size();
            auto hints=predictor.predict(argv[1],steps[j-1],3,32);
            uint64_t used=0;bool any=false;
            std::set<std::string> seen;
            for(const auto& h:hints){
                auto n=h.element.byte_count();
                if(n>budget-used||!seen.insert(h.element.key()).second)continue;
                used+=n;any=true;++predicted;
                if(target.count(h.element.key()))++hits;else wasted+=n;
            }
            bytes+=used;if(any)++covered;
        }
        std::cout<<"trained_transitions="<<trained<<"\nevaluated_transitions="<<evaluated
                 <<"\ncovered_transitions="<<covered<<"\nactual_hot_elements="<<actual
                 <<"\npredicted_elements="<<predicted<<"\nhits="<<hits
                 <<"\nrecall="<<(actual?double(hits)/actual:0)
                 <<"\nprecision="<<(predicted?double(hits)/predicted:0)
                 <<"\nhint_bytes="<<bytes<<"\nunmatched_hint_bytes="<<wasted<<"\n";
        return 0;
    }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}
}
