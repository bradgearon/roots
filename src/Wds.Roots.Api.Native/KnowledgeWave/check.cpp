#include "roots/knowledge_wave.hpp"
#include <sstream>
#include <iostream>
int main(){
    using namespace roots::wave;
    Request req(2);
    Range r{"talker","fixture.weight",128,4,4};
    req.begin("prefill");req.dependency(r);req.lookup(r,3);
    const float a[]={0,0,3,4,0,0,3,4};
    req.observe(r,a,4,2);
    auto first=req.finish();auto ranked=first.ranked(4);
    if(ranked.size()!=3 || first.elements.size()!=4)return 1;
    bool found=false;
    for(const auto& e:ranked)if(e.kind=="flow" && e.first==2 && e.share==1 &&
        e.byte_offset()==136 && e.byte_count()==8)found=true;
    if(!found)return 2;
    req.begin("decode");
    Range k{"talker","fixture.k",256,8,2};
    req.observe(k,a,4,2,2);auto next=req.finish();
    if(next.elements.size()!=1 || next.elements[0].share!=1)return 3;
    Lookahead look(2);
    if(!look.predict("en",first).empty())return 4;
    for(int i=0;i<3;++i)look.train("en",first,next);
    auto p=look.predict("en",first);
    if(p.size()!=1 || p[0].probability!=1 || p[0].examples!=3 ||
       p[0].element.range.tensor!="fixture.k" || !look.predict("ja",first).empty())return 5;
    bool rejected=false;req.begin("lookup");
    try{req.lookup(r,4);}catch(const std::invalid_argument&){rejected=true;}
    if(!rejected)return 6;
    std::ostringstream out;trace_header(out);trace_step(out,0,first);
    if(out.str().find("talker\tfixture.weight\t128\t4\t4\t2\t2\tflow\t1")==std::string::npos)return 7;
    std::cout<<"PASS: physical range, flow, fused slices, lookahead, unknown context, bounds\n";
}
