#include "roots_tree.h"
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <chrono>
namespace {
void need(bool b,const char *why){if(!b)throw std::runtime_error(why);}
void ok(roots_tree_result r){need(r==ROOTS_TREE_OK,"native operation failed");}
struct item{int64_t seq;std::string body,source,external;};
int collect(void *p,const roots_fragment *f){static_cast<std::vector<item>*>(p)->push_back({f->sequence,f->body,f->source,f->external_id});return 0;}
std::vector<item> query(roots_tree *t,const char *term="",int64_t agent=0,int64_t before=0,unsigned limit=100){
    std::vector<item> result;ok(roots_tree_query(t,term,agent,before,limit,collect,&result));return result;
}
int record_member(void *p,int64_t master,const roots_fragment *f){
    static_cast<std::vector<std::pair<int64_t,std::string>>*>(p)->emplace_back(master,f->body);return 0;
}
int edge(void *p,const char *s,const char *pred,const char *o){static_cast<std::vector<std::string>*>(p)->push_back(std::string(s)+" "+pred+" "+o);return 0;}
int agent(void *p,int64_t,const char*,const char*){++*static_cast<int*>(p);return 0;}
struct reader {
    std::ifstream f;
    explicit reader(const std::filesystem::path &p):f(p,std::ios::binary){}
    uint8_t byte(){int v=f.get();need(v!=EOF,"short GGUF");return static_cast<uint8_t>(v);}
    uint32_t u32(){uint32_t r=0;for(unsigned i=0;i<4;++i)r|=uint32_t(byte())<<(8*i);return r;}
    uint64_t u64(){uint64_t r=0;for(unsigned i=0;i<8;++i)r|=uint64_t(byte())<<(8*i);return r;}
    std::string str(){auto n=u64();need(n<100000,"unbounded metadata");std::string s;for(uint64_t i=0;i<n;++i)s+=static_cast<char>(byte());return s;}
};
void check_gguf(const std::filesystem::path &path,size_t n,int64_t revision){
    reader r(path);
    need(r.byte()=='G'&&r.byte()=='G'&&r.byte()=='U'&&r.byte()=='F',"GGUF magic");
    need(r.u32()==3&&r.u64()==1,"GGUF version/count");
    auto kv=r.u64();need(kv==9,"metadata count");
    uint64_t nodes=0,edges=0;bool rev=false;
    for(uint64_t i=0;i<kv;++i){
        auto name=r.str();auto type=r.u32();
        if(type==8){r.str();}
        else if(type==4){need(r.u32()==32,"alignment");}
        else if(type==10){need(r.u64()==static_cast<uint64_t>(revision),"revision");rev=true;}
        else if(type==9){
            need(r.u32()==8,"array type");auto count=r.u64();
            if(name=="roots.node.keys")nodes=count;
            if(name=="roots.edge.subject")edges=count;
            if(name=="roots.edge.predicate"||name=="roots.edge.object")need(count==edges,"edge array length");
            for(uint64_t j=0;j<count;++j)r.str();
        }else throw std::runtime_error("unknown metadata");
    }
    need(nodes==n&&rev&&edges>=2*n,"metadata values");
    need(r.str()=="node.embedding"&&r.u32()==2&&r.u64()==128&&r.u64()==n&&r.u32()==0&&r.u64()==0,"tensor descriptor");
    while(static_cast<uint64_t>(r.f.tellg())%32)need(r.byte()==0,"padding");
    for(size_t i=0;i<n;++i){double norm=0;for(unsigned j=0;j<128;++j){auto b=r.u32();float v;std::memcpy(&v,&b,4);need(std::isfinite(v),"finite tensor");norm+=double(v)*v;}need(std::abs(norm-1.0)<1e-4,"vector norm");}
    need(r.f.get()==EOF,"unexpected tensor bytes");
}
}
int main(){
    auto name="roots-tree-check-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count());
    auto dir=std::filesystem::temp_directory_path()/name;
    std::filesystem::create_directories(dir);
    roots_tree *t=nullptr;
    try {
        auto db=(dir/"tree.db").u8string();
        ok(roots_tree_open(db.c_str(),&t));need(roots_tree_abi_version()==1,"abi");
        {
            roots_observation parts[]={{"record.x.body","local:x:a","body one","en",1},
                {"record.x.author","local:x:a","name","en",1},
                {"record.x.body","local:x:b","body two","en",2},
                {"record.x.author","local:x:b","name","en",2}};
            size_t n=0;ok(roots_tree_observe(t,parts,4,&n));need(n==4,"distinct master membership");
            std::vector<std::pair<int64_t,std::string>> records;
            ok(roots_tree_records(t,"body two",0,0,1,record_member,&records));
            need(records.size()==2&&records[0].first==records[1].first,"whole matching master");
            auto cursor=records[0].first;records.clear();
            ok(roots_tree_records(t,"",0,cursor,1,record_member,&records));
            need(records.size()==2&&records[0].first<cursor,"whole master paging");
            ok(roots_tree_clear(t));
        }
        const char *parts[]={"Is","Part","Of"};char key[64];
        ok(roots_tree_predicate(parts,3,key,sizeof(key)));need(std::string(key)=="is.part.of","predicate components");
        const char *bad[]={"is","a.b"};need(roots_tree_predicate(bad,2,key,sizeof(key))==ROOTS_TREE_INVALID,"predicate injection");
        roots_observation first[]={{"x","1","Music and composition","en",1},{"x","2","Music and composition","en",2},{"x","","Japanese games","en",3}};
        size_t added=0;ok(roots_tree_observe(t,first,3,&added));need(added==3,"different IDs");
        ok(roots_tree_observe(t,first,3,&added));need(added==0,"dedup");
        need(query(t,"MUSIC").size()==2,"literal search");
        auto page=query(t,"",0,0,2);need(page.size()==2&&query(t,"",0,page.back().seq).size()==1,"paging");
        int64_t branch=0;ok(roots_tree_agent_create(t,"Music","music",&branch));
        need(query(t,"",branch).size()==2,"agent backfill");
        roots_observation next={"rss","3","New music study","en",4};
        ok(roots_tree_observe(t,&next,1,&added));need(added==1&&query(t,"",branch).size()==3,"agent growth");
        std::vector<std::string> relations;ok(roots_tree_relations(t,query(t,"New").front().seq,edge,&relations));
        need(relations.size()==3&&relations[0].find("is.a")!=std::string::npos&&relations[2].find("is.in")!=std::string::npos,"edge components");
        int agents=0;ok(roots_tree_agent_list(t,agent,&agents));need(agents==1,"agent list");
        roots_observation invalid[]={{"x","4","valid","en",5},{"x","5","","en",5}};
        need(roots_tree_observe(t,invalid,2,&added)==ROOTS_TREE_INVALID,"validate entire batch");
        need(std::strlen(roots_tree_last_error())>0,"native error detail");
        need(query(t).size()==4,"invalid batch rollback");
        std::atomic<bool> success{true};
        std::vector<std::thread> workers;
        for(int i=0;i<4;++i)workers.emplace_back([&,i]{for(int j=0;j<25;++j){
            std::string id=std::to_string(i)+"-"+std::to_string(j);
            roots_observation o={"fixture",id.c_str(),"parallel fixture","en",6};size_t n=0;
            if(roots_tree_observe(t,&o,1,&n)!=ROOTS_TREE_OK||n!=1)success=false;
        }});
        for(auto &w:workers)w.join();need(success,"parallel ingestion");
        int64_t count=0;ok(roots_tree_count(t,&count));need(count==104,"parallel count");
        int64_t rev=0,snapshot_rev=0;ok(roots_tree_revision(t,&rev));
        auto gguf=dir/"tree.gguf";auto gp=gguf.u8string();
        ok(roots_tree_compile(t,gp.c_str(),&snapshot_rev));need(snapshot_rev==rev,"snapshot revision");check_gguf(gguf,104,rev);
        need(roots_tree_compile(t,gp.c_str(),&snapshot_rev)==ROOTS_TREE_INVALID,"no overwrite");
        roots_tree_close(t);t=nullptr;
        ok(roots_tree_open(db.c_str(),&t));ok(roots_tree_count(t,&count));need(count==104,"restart persistence");
        need(query(t,"",branch).size()==3,"branch persistence");
        ok(roots_tree_agent_remove(t,branch));need(query(t,"",branch).empty(),"agent removal");
        ok(roots_tree_clear(t));ok(roots_tree_count(t,&count));need(count==0,"clear");
        roots_tree_close(t);t=nullptr;std::filesystem::remove_all(dir);
        std::cout<<"Roots native Tree contract checks passed\n";return 0;
    }catch(const std::exception &e){roots_tree_close(t);std::error_code ec;std::filesystem::remove_all(dir,ec);std::cerr<<e.what()<<'\n';return 1;}
}
