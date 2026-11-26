#include "roots_tree.h"
#include <jni.h>
#include <cstdint>
#include <string>
#include <vector>
#include <stdexcept>
#include <limits>

namespace {
roots_tree *ptr(jlong h) {
    if(!h)throw std::invalid_argument("closed Tree");
    return reinterpret_cast<roots_tree*>(static_cast<intptr_t>(h));
}
void checked(roots_tree_result r) {
    if(r!=ROOTS_TREE_OK)throw std::runtime_error("Tree result "+std::to_string(int(r)));
}
void fail(JNIEnv *e,const char *s) {
    if(e->ExceptionCheck())return;
    jclass c=e->FindClass("java/lang/IllegalStateException");
    if(c)e->ThrowNew(c,s);
}
template<class R,class F> R guard(JNIEnv *e,R fallback,F f) noexcept {
    try{return f();}catch(const std::exception &x){fail(e,x.what());}catch(...){fail(e,"Tree native failure");}
    return fallback;
}
std::string bytes(JNIEnv *e,jbyteArray value) {
    if(!value)throw std::invalid_argument("null UTF-8 input");
    auto n=e->GetArrayLength(value);
    if(n>32768)throw std::invalid_argument("UTF-8 input exceeds limit");
    std::string s(static_cast<size_t>(n),'\0');
    if(n)e->GetByteArrayRegion(value,0,n,reinterpret_cast<jbyte*>(s.data()));
    if(e->ExceptionCheck())throw std::runtime_error("JNI input");
    if(s.find('\0')!=std::string::npos)throw std::invalid_argument("embedded NUL");
    return s;
}
jobjectArray output(JNIEnv *e,const std::vector<std::string> &fields) {
    jclass type=e->FindClass("[B");
    if(!type)return nullptr;
    jobjectArray out=e->NewObjectArray(static_cast<jsize>(fields.size()),type,nullptr);
    e->DeleteLocalRef(type);
    if(!out)return nullptr;
    for(size_t i=0;i<fields.size();++i) {
        const auto &s=fields[i];
        auto a=e->NewByteArray(static_cast<jsize>(s.size()));
        if(!a)return nullptr;
        if(!s.empty())e->SetByteArrayRegion(a,0,static_cast<jsize>(s.size()),reinterpret_cast<const jbyte*>(s.data()));
        if(!e->ExceptionCheck())e->SetObjectArrayElement(out,static_cast<jsize>(i),a);
        e->DeleteLocalRef(a);
        if(e->ExceptionCheck())return nullptr;
    }
    return out;
}
struct Fields {std::vector<std::string> data;bool failed=false;};
int collect(void *user,const roots_fragment *r) {
    auto &f=*static_cast<Fields*>(user);
    try {
        f.data.insert(f.data.end(),{std::to_string(r->sequence),r->source,r->external_id,r->body,r->language,std::to_string(r->observed_ms)});
        return 0;
    }catch(...){f.failed=true;return 1;}
}
int agents(void *user,int64_t id,const char *name,const char *term) {
    auto &f=*static_cast<Fields*>(user);
    try{f.data.insert(f.data.end(),{std::to_string(id),name,term});return 0;}
    catch(...){f.failed=true;return 1;}
}
}
#define JNI_TREE(name) Java_com_wds_sixstars_contextoverlay_tree_NativeTree_##name
extern "C" {
JNIEXPORT jint JNICALL JNI_TREE(nAbi)(JNIEnv*,jclass){return static_cast<jint>(roots_tree_abi_version());}
JNIEXPORT jlong JNICALL JNI_TREE(nOpen)(JNIEnv *e,jclass,jbyteArray path) {
    return guard<jlong>(e,0,[&]{auto p=bytes(e,path);roots_tree *t=nullptr;checked(roots_tree_open(p.c_str(),&t));return static_cast<jlong>(reinterpret_cast<intptr_t>(t));});
}
JNIEXPORT void JNICALL JNI_TREE(nClose)(JNIEnv*,jclass,jlong h){roots_tree_close(reinterpret_cast<roots_tree*>(static_cast<intptr_t>(h)));}
JNIEXPORT jlong JNICALL JNI_TREE(nObserve)(JNIEnv *e,jclass,jlong h,jobjectArray data,jlongArray times) {
    return guard<jlong>(e,0,[&]{
        if(!data||!times)throw std::invalid_argument("missing batch");
        jsize n=e->GetArrayLength(times);
        if(n<1||n>256||e->GetArrayLength(data)!=n*4)throw std::invalid_argument("batch shape");
        std::vector<jlong> at(static_cast<size_t>(n));
        e->GetLongArrayRegion(times,0,n,at.data());
        if(e->ExceptionCheck())throw std::runtime_error("JNI timestamps");
        std::vector<std::string> strings;strings.reserve(static_cast<size_t>(n)*4);
        for(jsize i=0;i<n*4;++i){
            auto v=static_cast<jbyteArray>(e->GetObjectArrayElement(data,i));
            if(e->ExceptionCheck())throw std::runtime_error("JNI element");
            strings.push_back(bytes(e,v));e->DeleteLocalRef(v);
        }
        std::vector<roots_observation> rows;rows.reserve(static_cast<size_t>(n));
        for(jsize i=0;i<n;++i){
            auto k=static_cast<size_t>(i)*4;
            rows.push_back({strings[k].c_str(),strings[k+1].c_str(),strings[k+2].c_str(),strings[k+3].c_str(),static_cast<int64_t>(at[i])});
        }
        size_t inserted=0;checked(roots_tree_observe(ptr(h),rows.data(),rows.size(),&inserted));return static_cast<jlong>(inserted);
    });
}
JNIEXPORT jobjectArray JNICALL JNI_TREE(nQuery)(JNIEnv *e,jclass,jlong h,jbyteArray term,jlong agent,jlong before,jint limit) {
    return guard<jobjectArray>(e,nullptr,[&]{auto q=bytes(e,term);Fields f;checked(roots_tree_query(ptr(h),q.c_str(),agent,before,static_cast<uint32_t>(limit),collect,&f));if(f.failed)throw std::runtime_error("query collection failed");return output(e,f.data);});
}
JNIEXPORT jobjectArray JNICALL JNI_TREE(nAgents)(JNIEnv *e,jclass,jlong h) {
    return guard<jobjectArray>(e,nullptr,[&]{Fields f;checked(roots_tree_agent_list(ptr(h),agents,&f));if(f.failed)throw std::runtime_error("agent collection failed");return output(e,f.data);});
}
JNIEXPORT jlong JNICALL JNI_TREE(nCreateAgent)(JNIEnv *e,jclass,jlong h,jbyteArray name,jbyteArray term) {
    return guard<jlong>(e,0,[&]{auto a=bytes(e,name),b=bytes(e,term);int64_t id=0;checked(roots_tree_agent_create(ptr(h),a.c_str(),b.c_str(),&id));return static_cast<jlong>(id);});
}
JNIEXPORT void JNICALL JNI_TREE(nRemoveAgent)(JNIEnv *e,jclass,jlong h,jlong id) {
    guard<int>(e,0,[&]{checked(roots_tree_agent_remove(ptr(h),id));return 0;});
}
JNIEXPORT jlong JNICALL JNI_TREE(nCount)(JNIEnv *e,jclass,jlong h) {
    return guard<jlong>(e,0,[&]{int64_t n=0;checked(roots_tree_count(ptr(h),&n));return static_cast<jlong>(n);});
}
JNIEXPORT jlong JNICALL JNI_TREE(nRevision)(JNIEnv *e,jclass,jlong h) {
    return guard<jlong>(e,0,[&]{int64_t n=0;checked(roots_tree_revision(ptr(h),&n));return static_cast<jlong>(n);});
}
JNIEXPORT jlong JNICALL JNI_TREE(nCompile)(JNIEnv *e,jclass,jlong h,jbyteArray path) {
    return guard<jlong>(e,0,[&]{auto p=bytes(e,path);int64_t rev=0;checked(roots_tree_compile(ptr(h),p.c_str(),&rev));return static_cast<jlong>(rev);});
}
JNIEXPORT void JNICALL JNI_TREE(nClear)(JNIEnv *e,jclass,jlong h) {
    guard<int>(e,0,[&]{checked(roots_tree_clear(ptr(h)));return 0;});
}
}
