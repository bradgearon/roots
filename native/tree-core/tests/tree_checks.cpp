#include "roots_tree.h"
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>
#include <map>
#include <set>
#include <tuple>
#include <thread>
#include <atomic>
#include <chrono>
#include <iterator>
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
void check_gguf(const std::filesystem::path &path,size_t n,int64_t revision,
                const std::vector<item> &expected,bool structured=false){
    reader r(path);
    need(r.byte()=='G'&&r.byte()=='G'&&r.byte()=='U'&&r.byte()=='F',"GGUF magic");
    need(r.u32()==3&&r.u64()==1,"GGUF version/count");
    auto kv=r.u64();need(kv==18,"metadata count");
    std::map<std::string,std::vector<std::string>> arrays;
    std::vector<uint64_t> timestamps;
    bool rev=false,format=false;
    for(uint64_t i=0;i<kv;++i){
        auto name=r.str();auto type=r.u32();
        if(type==8){r.str();}
        else if(type==4){
            auto value=r.u32();
            if(name=="general.alignment")need(value==32,"alignment");
            else if(name=="roots.format.version"){need(value==2,"format version");format=true;}
            else throw std::runtime_error("unexpected uint32");
        }
        else if(type==10){need(name=="roots.tree.revision"&&r.u64()==static_cast<uint64_t>(revision),"revision");rev=true;}
        else if(type==9){
            auto element=r.u32();auto count=r.u64();
            if(name=="roots.node.observed_ms"){
                need(element==10&&count==n,"timestamp array");
                for(uint64_t j=0;j<count;++j)timestamps.push_back(r.u64());
            }else{
                need(element==8,"string array type");
                auto &values=arrays[name];for(uint64_t j=0;j<count;++j)values.push_back(r.str());
            }
        }else throw std::runtime_error("unknown metadata");
    }
    for(auto name:{"roots.node.keys","roots.node.text","roots.node.source","roots.node.external_id","roots.node.language"})
        need(arrays[name].size()==n,"node metadata alignment");
    need(rev&&format&&timestamps.size()==n,"metadata values");
    auto &subjects=arrays["roots.edge.subject"],&predicates=arrays["roots.edge.predicate"],&objects=arrays["roots.edge.object"];
    need(subjects.size()>=2*n&&subjects.size()==predicates.size()&&subjects.size()==objects.size(),"edge array lengths");
    std::set<std::tuple<std::string,std::string,std::string>> edges;
    for(size_t i=0;i<subjects.size();++i)need(edges.emplace(subjects[i],predicates[i],objects[i]).second,"duplicate edge");
    if(structured){
        need(edges.count({"master:local:x:a","is.in","network:x"})==1,"master network edge");
        need(arrays["roots.branch.name"].empty(),"no branches");
        need(arrays["roots.node.text"][0]==u8"日本語 😀\nsecond line","lossless unicode and newline");
    }else{
        need(arrays["roots.branch.name"]==std::vector<std::string>{"Music"},"branch metadata");
        need(arrays["roots.branch.term"]==std::vector<std::string>{"music"},"branch term");
    }
    for(const auto &e:expected){
        auto key="fragment:"+std::to_string(e.seq);
        bool found=false;
        for(size_t i=0;i<n;++i)if(arrays["roots.node.keys"][i]==key){
            need(arrays["roots.node.text"][i]==e.body&&arrays["roots.node.source"][i]==e.source&&arrays["roots.node.external_id"][i]==e.external,"exact source row roundtrip");
            found=true;break;
        }
        need(found,"missing fragment key");
    }
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
            roots_observation parts[]={{"record.x.body","local:x:a",u8"日本語 😀\nsecond line","ja",1},
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
            auto path=dir/"structured.gguf";int64_t revision=0;
            ok(roots_tree_compile(t,path.u8string().c_str(),&revision));
            check_gguf(path,4,revision,query(t),true);
            ok(roots_tree_clear(t));
        }
        {
            roots_observation localized[]={
                {"record.x.body","local:x:localization","ORIGINAL_JA_PRIVATE_SENTINEL","ja",1},
                {"record.x.en.translation","local:x:localization","OLD_EN_TRANSLATION_SENTINEL","en",2},
                {"record.x.en.translation","local:x:localization","LATEST_EN_TRANSLATION_SENTINEL","en",3},
                {"record.x.en.understanding","local:x:localization","UNDERSTANDING_EN_SENTINEL","en",4},
                {"record.x.author","local:x:localization","PRIVATE_AUTHOR_SENTINEL","en",1},
                {"record.mesh.body","local:mesh:example","RECEIVED_EN_SENTINEL","en",2},
                {"record.mesh.offer","local:mesh:example","OFFER_PRIVATE_METADATA_SENTINEL","",2},
                {"raw.x","","RAW_PRIVATE_SENTINEL","en",1},
                {"record.x.body","local:x:unknown","UNKNOWN_LANGUAGE_SENTINEL","",2}};
            size_t n=0;ok(roots_tree_observe(t,localized,9,&n));need(n==9,"localized rows");
            auto path=dir/"localized.gguf";int64_t revision=0;
            ok(roots_tree_compile_localized(t,path.u8string().c_str(),"en",&revision));
            std::ifstream stream(path,std::ios::binary);std::string bytes((std::istreambuf_iterator<char>(stream)),std::istreambuf_iterator<char>());
            for(auto expected:{"LATEST_EN_TRANSLATION_SENTINEL","UNDERSTANDING_EN_SENTINEL","RECEIVED_EN_SENTINEL","roots.local.language"})
                need(bytes.find(expected)!=std::string::npos,"localized GGUF content");
            for(auto excluded:{"ORIGINAL_JA_PRIVATE_SENTINEL","OLD_EN_TRANSLATION_SENTINEL","PRIVATE_AUTHOR_SENTINEL","OFFER_PRIVATE_METADATA_SENTINEL","RAW_PRIVATE_SENTINEL","UNKNOWN_LANGUAGE_SENTINEL"})
                need(bytes.find(excluded)==std::string::npos,"private text excluded from localized GGUF");
            int64_t count=0;ok(roots_tree_count(t,&count));need(count==9,"originals retained in DB");
            auto empty=dir/"empty-localized.gguf";ok(roots_tree_compile_localized(t,empty.u8string().c_str(),"fr",&revision));
            need(!std::filesystem::exists(empty),"empty local projection");
            stream.close();ok(roots_tree_clear(t));
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
        ok(roots_tree_compile(t,gp.c_str(),&snapshot_rev));need(snapshot_rev==rev,"snapshot revision");check_gguf(gguf,104,rev,query(t));
        need(roots_tree_compile(t,gp.c_str(),&snapshot_rev)==ROOTS_TREE_INVALID,"no overwrite");
        roots_tree_close(t);t=nullptr;
        ok(roots_tree_open(db.c_str(),&t));ok(roots_tree_count(t,&count));need(count==104,"restart persistence");
        need(query(t,"",branch).size()==3,"branch persistence");
        ok(roots_tree_agent_remove(t,branch));need(query(t,"",branch).empty(),"agent removal");
        ok(roots_tree_clear(t));ok(roots_tree_count(t,&count));need(count==0,"clear");
        // The original storage ceiling must never reject new records.
        for(int start=0;start<10240;start+=128){
            std::vector<std::string> ids;ids.reserve(128);
            std::vector<roots_observation> batch;batch.reserve(128);
            for(int i=0;i<128;i++)ids.push_back("local:x:growth:"+std::to_string(start+i));
            for(const auto &id:ids)batch.push_back({"record.x.body",id.c_str(),"Growth regression body","en",7});
            ok(roots_tree_observe(t,batch.data(),batch.size(),&added));need(added==128,"capture beyond former cap");
        }
        ok(roots_tree_count(t,&count));need(count==10240,"all retained");
        roots_observation beyond={"record.x.body","local:x:after-cap","New knowledge after cap","en",8};
        ok(roots_tree_observe(t,&beyond,1,&added));need(added==1,"new record after cap");
        ok(roots_tree_observe(t,&beyond,1,&added));need(added==0,"dedup after cap");
        auto active=dir/"active-window.gguf";
        ok(roots_tree_compile_localized(t,active.u8string().c_str(),"en",&snapshot_rev));
        need(std::filesystem::exists(active),"active projection");
        ok(roots_tree_count(t,&count));need(count==10241,"projection never deletes older knowledge");
        roots_tree_close(t);t=nullptr;std::filesystem::remove_all(dir);
        std::cout<<"Roots native Tree contract checks passed\n";return 0;
    }catch(const std::exception &e){roots_tree_close(t);std::error_code ec;std::filesystem::remove_all(dir,ec);std::cerr<<e.what()<<'\n';return 1;}
}
