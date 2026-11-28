#include "roots_tree.h"
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <string>
#include <chrono>
#define CHECK(x) do { if(!(x)){std::cerr<<"failed at "<<__LINE__<<" : "<<#x<<"\n";std::abort();} } while(false)
struct item {int64_t id=0,token=0;int state=-1;std::string output;};
int capture(void *u,const roots_work *w){auto &i=*static_cast<item*>(u);i={w->id,w->token,w->state,w->output};return 0;}
int main(){
    auto path=std::filesystem::temp_directory_path()/("roots-work-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count())+".db");
    auto name=path.u8string();roots_tree *t=nullptr;CHECK(roots_tree_open(name.c_str(),&t)==0);
    roots_observation rows[]={{"test","","first","ja",1},{"test","","second","ja",2}};size_t inserted=0;
    CHECK(roots_tree_observe(t,rows,2,&inserted)==0&&inserted==2);
    int64_t a=0,b=0,duplicate=0;
    CHECK(roots_work_request(t,1,"translate","en:v1",10,0,&a)==0);
    CHECK(roots_work_request(t,2,"translate","en:v1",20,0,&b)==0);
    CHECK(roots_work_request(t,1,"translate","en:v1",100,0,&duplicate)==0&&duplicate==a);
    item first;CHECK(roots_work_claim(t,"translate","phone",100,50,capture,&first)==0&&first.id==a);
    item second;CHECK(roots_work_claim(t,"translate","desktop",100,50,capture,&second)==0&&second.id==b);
    CHECK(roots_work_finish(t,b,second.token,"desktop",110,ROOTS_WORK_COMPLETED,"result",0)==0);
    roots_tree_close(t);CHECK(roots_tree_open(name.c_str(),&t)==0);
    item done;CHECK(roots_work_read(t,b,capture,&done)==0&&done.output=="result");
    item reclaimed;CHECK(roots_work_claim(t,"translate","desktop",151,50,capture,&reclaimed)==0&&reclaimed.id==a&&reclaimed.token>first.token);
    CHECK(roots_work_finish(t,a,first.token,"phone",152,ROOTS_WORK_COMPLETED,"stale",0)==ROOTS_TREE_STALE);
    CHECK(roots_work_renew(t,a,reclaimed.token,"desktop",152,100)==0);
    CHECK(roots_work_finish(t,a,reclaimed.token,"desktop",160,ROOTS_WORK_PENDING,"",200)==0);
    item none;CHECK(roots_work_claim(t,"translate","phone",199,50,capture,&none)==0&&none.id==0);
    CHECK(roots_work_claim(t,"translate","phone",200,50,capture,&none)==0&&none.id==a);
    CHECK(roots_work_cancel(t,a)==0);
    CHECK(roots_work_finish(t,a,none.token,"phone",201,ROOTS_WORK_COMPLETED,"late",0)==ROOTS_TREE_STALE);
    CHECK(roots_tree_clear(t)==0);item removed;CHECK(roots_work_read(t,b,capture,&removed)==0&&removed.id==0);
    roots_tree_close(t);std::filesystem::remove(path);std::cout<<"work checks passed\n";
}
