#include <jni.h>
#include <llama.h>
#include <ggml-backend.h>
#include <ggml.h>
#include <gguf.h>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <set>
#include <tuple>
#include <sstream>
#include <iomanip>
#include <locale>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <cstdlib>
#include <sys/auxv.h>
#include <asm/hwcap.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <android/log.h>

namespace {
std::mutex gate, log_gate;
// Independent model/context slots share one engine and serialized execution.
// A FunctionGemma request cannot replace PLaMo's resident weights or context.
// Aggregate only normalized group energy. Never retain tokens or activations.
struct flow_key {
    int phase,layer,group;
    std::string family;
    bool operator<(const flow_key &r)const{
        return std::tie(phase,layer,family,group)<std::tie(r.phase,r.layer,r.family,r.group);
    }
};
struct flow_value { double sum=0; unsigned samples=0; };
struct flow_observer {
    bool enabled=false, sampling=false;
    int phase=0;
    std::map<flow_key,flow_value> values;
    std::vector<std::pair<int,int>> plan;
    size_t advised=0;
    unsigned skipped=0;
    void reset(bool on){enabled=on;sampling=false;phase=0;values.clear();plan.clear();advised=0;skipped=0;}
};
static bool flow_tensor(const ggml_tensor *t,int &layer,std::string &family){
    const char *name=ggml_get_name(t);
    if(!name)return false;
    const char *dash=std::strrchr(name,'-');if(!dash||!dash[1])return false;
    char *end=nullptr;long value=std::strtol(dash+1,&end,10);
    if(!end||*end||value<0||value>=128)return false;
    std::string stem(name,static_cast<size_t>(dash-name));
    if(stem=="ffn_geglu"||stem=="ffn_swiglu"||stem=="ffn_gate_par")family="ffn";
    else if(stem=="attn_post_norm")family="attention";
    else if(stem=="l_out")family="residual";
    else return false;
    layer=static_cast<int>(value);return true;
}
static bool observe_flow(ggml_tensor *t,bool ask,void *opaque){
    auto &f=*static_cast<flow_observer*>(opaque);
    if(!f.enabled||!f.sampling)return !ask;
    try{
        int layer=0;std::string family;
        if(!flow_tensor(t,layer,family))return !ask;
        if(t->type!=GGML_TYPE_F32||!ggml_is_contiguous(t)||t->ne[0]<=0||t->ne[0]>32768||t->ne[2]!=1||t->ne[3]!=1||t->ne[1]<=0){
            if(ask)++f.skipped;return !ask;
        }
        if(ask)return true;
        // One last-token row from the selected prefill/decode evaluation.
        size_t width=static_cast<size_t>(t->ne[0]), bytes=width*sizeof(float);
        size_t total=ggml_nbytes(t);
        if(total<bytes){++f.skipped;return true;}
        std::vector<float> row(width);
        ggml_backend_tensor_get(t,row.data(),total-bytes,bytes);
        std::vector<double> groups((width+31)/32,0.0);double sum=0;
        for(size_t i=0;i<width;i++){
            double x=row[i];if(!std::isfinite(x)){++f.skipped;return true;}
            groups[i/32]+=x*x;sum+=x*x;
        }
        if(!(sum>0)||!std::isfinite(sum))return true;
        for(size_t i=0;i<groups.size();i++){
            flow_key key{f.phase,layer,static_cast<int>(i),family};
            auto &v=f.values[key];v.sum+=groups[i]/sum;++v.samples;
        }
    }catch(...){++f.skipped;}
    // Returning false in the delivery phase would abort the graph.
    return !ask;
}
struct model_slot {
    llama_model *model=nullptr;
    llama_context *resident_ctx=nullptr;
    ggml_backend_dev_t devices[2]={nullptr,nullptr};
    std::string backend="unloaded",path;
    flow_observer flow;
    bool context_observed=false;
};
model_slot translation_slot, function_slot, intelligence_slot;
using clock_type=std::chrono::steady_clock;
int64_t elapsed_ms(clock_type::time_point start){return std::chrono::duration_cast<std::chrono::milliseconds>(clock_type::now()-start).count();}
void release_context(model_slot &slot){if(slot.resident_ctx){llama_free(slot.resident_ctx);slot.resident_ctx=nullptr;}slot.context_observed=false;}
bool backend=false;
std::atomic<bool> collect_load_errors{false};
std::string load_errors;
constexpr size_t max_diagnostic=1800;
void engine_log(ggml_log_level level,const char *message,void *) {
    // Only collect model/backend loading errors, never prompt or completion logs.
    if(!collect_load_errors.load()||level!=GGML_LOG_LEVEL_ERROR||!message)return;
    try {
        std::lock_guard<std::mutex> lock(log_gate);
        if(load_errors.size()<max_diagnostic) {
            const std::string s(message);
            load_errors.append(s,0,std::min(s.size(),max_diagnostic-load_errors.size()));
        }
    }catch(...){}
}
void fail(JNIEnv *env,std::string message) {
    // JNI ThrowNew expects modified UTF-8; diagnostics are intentionally ASCII.
    for(char &c:message)if(static_cast<unsigned char>(c)>127||c=='\r')c='?';
    __android_log_write(ANDROID_LOG_ERROR,"RootsPLaMo",message.c_str());
    if(!env->ExceptionCheck()) {
        jclass cls=env->FindClass("java/lang/IllegalStateException");
        if(cls)env->ThrowNew(cls,message.c_str());
    }
}
struct load_trace {
    load_trace() {
        std::lock_guard<std::mutex> lock(log_gate);
        load_errors.clear();collect_load_errors.store(true);
    }
    ~load_trace(){collect_load_errors.store(false);}
};
std::string load_detail() {
    std::lock_guard<std::mutex> lock(log_gate);
    return load_errors.empty()?"":": "+load_errors;
}
}
extern "C" JNIEXPORT jstring JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_revision(JNIEnv *env,jclass) {
    return env->NewStringUTF(ROOTS_PLAMO_LLAMA_REVISION);
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_vulkanBuilt(JNIEnv *,jclass) {
    return ROOTS_VULKAN ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jstring JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_accelerationInfo(JNIEnv *env,jclass) {
    std::lock_guard<std::mutex> lock(gate);
    try {
        std::string info="kleidiai_built="+std::to_string(ROOTS_KLEIDIAI)+" vulkan_built="+std::to_string(ROOTS_VULKAN);
#ifdef HWCAP_ASIMDDP
        info+=" dotprod="+std::to_string(bool(getauxval(AT_HWCAP)&HWCAP_ASIMDDP));
#endif
#ifdef HWCAP2_I8MM
        info+=" i8mm="+std::to_string(bool(getauxval(AT_HWCAP2)&HWCAP2_I8MM));
#endif
#ifdef HWCAP_ASIMDHP
        info+=" fp16="+std::to_string(bool(getauxval(AT_HWCAP)&HWCAP_ASIMDHP));
#endif
        info+=" backend="+translation_slot.backend;
        if(backend) {const char *system=llama_print_system_info();if(system)info+=" "+std::string(system);}
        for(char &ch:info)if(static_cast<unsigned char>(ch)>127||ch=='\r'||ch=='\n')ch=' ';
        return env->NewStringUTF(info.c_str());
    } catch(...) {return env->NewStringUTF("acceleration information unavailable");}
}
static void load_model(JNIEnv *env,jstring path,jboolean use_vulkan,model_slot &slot) {
    std::lock_guard<std::mutex> lock(gate);
    if(!path){fail(env,"Model path missing");return;}
    const char *p=env->GetStringUTFChars(path,nullptr);
    if(!p)return;
    std::string stage="backend initialization";
    try {
        std::string filename(p);
        env->ReleaseStringUTFChars(path,p);p=nullptr;
        load_trace trace;
        if(!backend) {
            llama_log_set(engine_log,nullptr);
            llama_backend_init();
            backend=true;
        }
        stage="model load";
        auto mp=llama_model_default_params();
        // Free the old context before replacing the model or its device list.
        release_context(slot);
        if(slot.model){llama_model_free(slot.model);slot.model=nullptr;}
        slot.backend="unloaded";
        slot.devices[0]=nullptr;slot.devices[1]=nullptr;
        mp.devices=slot.devices;
        mp.n_gpu_layers=0;
        std::string requested="CPU";
        if(use_vulkan==JNI_TRUE) {
#if ROOTS_VULKAN
            auto device=ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_GPU);
            if(!device)device=ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_IGPU);
            if(!device)throw std::runtime_error("No supported Vulkan GPU was registered; select CPU");
            slot.devices[0]=device;
            mp.n_gpu_layers=-1;
            requested=std::string("Vulkan/")+ggml_backend_dev_name(device);
#else
            throw std::runtime_error("Vulkan not built; select CPU or rebuild with rootsEnableVulkan=true");
#endif
        }
        slot.model=llama_model_load_from_file(filename.c_str(),mp);
        if(!slot.model)throw std::runtime_error("llama_model_load_from_file returned null"+load_detail());
        slot.backend=requested;slot.path=filename;
        __android_log_write(ANDROID_LOG_INFO,"RootsPLaMo",("Model loaded: " ROOTS_PLAMO_LLAMA_REVISION " "+slot.backend).c_str());
    }catch(const std::exception &ex) {
        if(p)env->ReleaseStringUTFChars(path,p);
        fail(env,stage+" [" ROOTS_PLAMO_LLAMA_REVISION "]: "+ex.what());
    }catch(...) {
        if(p)env->ReleaseStringUTFChars(path,p);
        fail(env,stage+" [" ROOTS_PLAMO_LLAMA_REVISION "]: unknown native failure");
    }
}
static void unload_model(model_slot &slot) {
    std::lock_guard<std::mutex> lock(gate);
    release_context(slot);
    if(slot.model){llama_model_free(slot.model);slot.model=nullptr;}
    slot.backend="unloaded";slot.path.clear();slot.flow.reset(false);
}
// OS cache hints for exact quantized rows in FFN gate/up tensors. The down
// projection is column-oriented; don't pretend it is a contiguous group.
// No weights are removed or altered; dense inference always executes.
static void advise_flow(model_slot &slot){
    auto &flow=slot.flow;
    if(!flow.enabled||flow.plan.empty()||slot.path.empty())return;
    constexpr size_t budget=4*1024*1024;
    int fd=open(slot.path.c_str(),O_RDONLY|O_CLOEXEC);if(fd<0)return;
    struct fd_guard{int fd;~fd_guard(){close(fd);}} close_file{fd};
    struct stat st{};if(fstat(fd,&st)||st.st_size<=0)return;
    auto *raw=gguf_init_from_file(slot.path.c_str(),{true,nullptr});
    if(!raw)return;
    std::unique_ptr<gguf_context,decltype(&gguf_free)> meta(raw,gguf_free);
    size_t base=gguf_get_data_offset(raw);
    for(const auto &group:flow.plan){
        for(const char *kind:{"ffn_gate","ffn_up"}){
            std::string name="blk."+std::to_string(group.first)+"."+kind+".weight";
            int64_t index=gguf_find_tensor(raw,name.c_str());if(index<0)continue;
            auto *ne=gguf_get_tensor_ne(raw,index);
            if(!ne||ne[0]<=0||ne[1]<=0||ne[2]!=1||ne[3]!=1)continue;
            size_t size=gguf_get_tensor_size(raw,index), rows=static_cast<size_t>(ne[1]);
            if(!size||size%rows)continue;
            size_t row=size/rows, first=static_cast<size_t>(group.second)*32;
            if(first>=rows)continue;
            size_t length=std::min<size_t>(32,rows-first)*row;
            size_t offset=gguf_get_tensor_offset(raw,index);
            if(base>static_cast<uint64_t>(st.st_size)||offset>static_cast<uint64_t>(st.st_size)-base)continue;
            offset+=base;
            if(first*row>static_cast<uint64_t>(st.st_size)-offset)continue;
            offset+=first*row;
            if(length>static_cast<uint64_t>(st.st_size)-offset||length>budget-flow.advised)continue;
            if(posix_fadvise(fd,static_cast<off_t>(offset),static_cast<off_t>(length),POSIX_FADV_WILLNEED)==0)flow.advised+=length;
        }
    }
}
static jbyteArray generate_model(JNIEnv *env,jbyteArray input,jlongArray timings,jint requested_threads,model_slot &slot,bool functions,jobject listener=nullptr,bool intelligence=false) {
    std::lock_guard<std::mutex> lock(gate);
    // Contexts are independent but computation is serialized across model slots.
    // Reuse allocations, never retain a previous request's attention state.
    jlong stats[8]={}; // reused, setup_ms, tokenize_ms, prefill_ms, generation_ms, prompt_tokens, sampled_tokens, threads
    std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)> sampler(nullptr,llama_sampler_free);
    std::string stage="tokenization";
    try {
        if(!slot.model)throw std::runtime_error("Model not loaded");
        if(!input)throw std::runtime_error("Missing prompt");
        const int threads=std::max(1,std::min(8,static_cast<int>(requested_threads)));
        if(!timings||env->GetArrayLength(timings)!=8)throw std::runtime_error("Missing timing buffer");
        auto start=clock_type::now();
        std::string prompt(env->GetArrayLength(input),'\0');
        env->GetByteArrayRegion(input,0,static_cast<jsize>(prompt.size()),reinterpret_cast<jbyte*>(prompt.data()));
        if(env->ExceptionCheck())return nullptr;
        auto *vocab=llama_model_get_vocab(slot.model);
        int count=-llama_tokenize(vocab,prompt.data(),static_cast<int>(prompt.size()),nullptr,0,true,true);
        if(count<=0||count>((functions||intelligence)?1600:3000))throw std::runtime_error("Invalid prompt token count: "+std::to_string(count));
        std::vector<llama_token> tokens(count);
        count=llama_tokenize(vocab,prompt.data(),static_cast<int>(prompt.size()),tokens.data(),static_cast<int>(tokens.size()),true,true);
        if(count<=0||count>static_cast<int>(tokens.size()))throw std::runtime_error("Tokenization failed");
        stats[2]=elapsed_ms(start);stats[5]=count;
        stage="context setup";start=clock_type::now();
        bool observing=intelligence&&slot.flow.enabled;
        if(slot.resident_ctx&&slot.context_observed!=observing)release_context(slot);
        if(observing){try{advise_flow(slot);}catch(...){++slot.flow.skipped;}}
        if(slot.resident_ctx){
            llama_synchronize(slot.resident_ctx);
            auto memory=llama_get_memory(slot.resident_ctx);
            if(memory){llama_memory_clear(memory,true);stats[0]=1;}
            else release_context(slot);
        }
        if(!slot.resident_ctx){
            auto cp=llama_context_default_params();
            cp.n_ctx=(functions||intelligence)?2048:4096;cp.n_batch=256;cp.n_ubatch=128;cp.n_seq_max=1;
            cp.n_threads=threads;cp.n_threads_batch=threads;
            if(observing){cp.cb_eval=observe_flow;cp.cb_eval_user_data=&slot.flow;}
            slot.resident_ctx=llama_init_from_model(slot.model,cp);
            slot.context_observed=observing;
            if(!slot.resident_ctx)throw std::runtime_error("Could not allocate context");
        }
        auto *ctx=slot.resident_ctx;
        llama_set_n_threads(ctx,threads,threads);
        stats[1]=elapsed_ms(start);stats[7]=llama_n_threads(ctx);
        stage="prompt decode";start=clock_type::now();
        for(int i=0;i<count;i+=256) {
            if(observing){slot.flow.phase=0;slot.flow.sampling=(i+256>=count);}
            int rc=llama_decode(ctx,llama_batch_get_one(tokens.data()+i,std::min(256,count-i)));
            if(rc)throw std::runtime_error("llama_decode code "+std::to_string(rc));
        }
        llama_synchronize(ctx);stats[3]=elapsed_ms(start);
        stage="sampling";start=clock_type::now();
        sampler.reset(llama_sampler_init_greedy());
        if(!sampler)throw std::runtime_error("Could not allocate sampler");
        std::string out;
        jmethodID callback=nullptr;
        if(listener){auto cls=env->GetObjectClass(listener);callback=env->GetMethodID(cls,"onPrefix","([B)V");env->DeleteLocalRef(cls);if(!callback||env->ExceptionCheck())return nullptr;}
        size_t sent=0;auto last=clock_type::now();
        auto emit=[&](bool final){
            if(!callback||out.size()<=sent||(!final&&sent&&elapsed_ms(last)<40))return;
            auto bytes=env->NewByteArray(static_cast<jsize>(out.size()));
            if(!bytes||env->ExceptionCheck())throw std::runtime_error("stream allocation");
            env->SetByteArrayRegion(bytes,0,static_cast<jsize>(out.size()),reinterpret_cast<const jbyte*>(out.data()));
            if(!env->ExceptionCheck())env->CallVoidMethod(listener,callback,bytes);
            env->DeleteLocalRef(bytes);if(env->ExceptionCheck())throw std::runtime_error("stream callback");
            sent=out.size();last=clock_type::now();
        };
        // Five compact movement resolutions must fit in a single call.
        // Ordinary function proposals retain their previous 160-token budget.
        const bool movement=functions&&prompt.find("declaration:resolve_movements{")!=std::string::npos;
        const int max_output=intelligence?384:(functions?(movement?384:160):512);
        for(int i=0;i<max_output;i++) {
            auto token=llama_sampler_sample(sampler.get(),ctx,-1);
            ++stats[6];
            const bool eog=llama_vocab_is_eog(vocab,token);
            if(eog&&!functions)break;
            std::vector<char> piece(512);
            int n=llama_token_to_piece(vocab,token,piece.data(),static_cast<int>(piece.size()),0,true);
            if(n<0&&n>=-16384){
                piece.resize(static_cast<size_t>(-n));
                n=llama_token_to_piece(vocab,token,piece.data(),static_cast<int>(piece.size()),0,true);
            }
            if(n<0||n>static_cast<int>(piece.size()))throw std::runtime_error("Token piece conversion failed");
            if(eog){
                // Some Gemma vocabularies mark the call terminator as EOG.
                if(functions&&std::string(piece.data(),n)=="<end_function_call>")out.append(piece.data(),n);
                break;
            }
            out.append(piece.data(),n);
            if(functions){
                const std::string end="<end_function_call>";
                auto stop=out.find(end);
                if(stop!=std::string::npos){out.resize(stop+end.size());break;}
                stop=out.find("<start_function_response>");
                if(stop!=std::string::npos){out.resize(stop);break;}
            }else if(intelligence){
                bool ended=false;
                for(const char *marker:{"<|im_end|>","<end_of_turn>","<|im_start|>","<start_of_turn>"}){
                    auto stop=out.find(marker);if(stop!=std::string::npos){out.resize(stop);ended=true;break;}
                }
                if(ended)break;
            }else{
                auto stop=out.find("<|plamo:");
                if(stop!=std::string::npos){out.resize(stop);break;}
            }
            emit(false);
            if(i==max_output-1)break;
            if(observing){slot.flow.phase=1;slot.flow.sampling=(i<8);}
            int rc=llama_decode(ctx,llama_batch_get_one(&token,1));
            if(rc)throw std::runtime_error("Generation decode code "+std::to_string(rc));
        }
        llama_synchronize(ctx);slot.flow.sampling=false;stats[4]=elapsed_ms(start);
        emit(true);
        stage="result transfer";
        env->SetLongArrayRegion(timings,0,8,stats);
        if(env->ExceptionCheck())return nullptr;
        jbyteArray result=env->NewByteArray(static_cast<jsize>(out.size()));
        if(result)env->SetByteArrayRegion(result,0,static_cast<jsize>(out.size()),reinterpret_cast<const jbyte*>(out.data()));
        return result;
    }catch(const std::exception &ex){release_context(slot);fail(env,stage+" [" ROOTS_PLAMO_LLAMA_REVISION "]: "+ex.what());return nullptr;}
    catch(...){release_context(slot);fail(env,stage+" [" ROOTS_PLAMO_LLAMA_REVISION "]: unknown native failure");return nullptr;}
}

extern "C" JNIEXPORT void JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_load(JNIEnv *env,jclass,jstring path,jboolean use_vulkan) {
    load_model(env,path,use_vulkan,translation_slot);
}
extern "C" JNIEXPORT void JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_unload(JNIEnv *,jclass) {
    unload_model(translation_slot);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_generate(JNIEnv *env,jclass,jbyteArray input,jlongArray timings,jint threads) {
    return generate_model(env,input,timings,threads,translation_slot,false);
}
extern "C" JNIEXPORT void JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_loadFunction(JNIEnv *env,jclass,jstring path) {
    // Start with the proven CPU path. GPU selection for functions is independent.
    load_model(env,path,JNI_FALSE,function_slot);
}
extern "C" JNIEXPORT void JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_unloadFunction(JNIEnv *,jclass) {
    unload_model(function_slot);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_generateFunction(JNIEnv *env,jclass,jbyteArray input,jlongArray timings,jint threads) {
    return generate_model(env,input,timings,threads,function_slot,true);
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_generateStreaming(JNIEnv *env,jclass,jbyteArray input,jlongArray timings,jint threads,jobject listener) {return generate_model(env,input,timings,threads,translation_slot,false,listener);}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_generateFunctionStreaming(JNIEnv *env,jclass,jbyteArray input,jlongArray timings,jint threads,jobject listener) {return generate_model(env,input,timings,threads,function_slot,true,listener);}

extern "C" JNIEXPORT void JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_loadIntelligence(JNIEnv *env,jclass,jstring path){load_model(env,path,JNI_FALSE,intelligence_slot);}
extern "C" JNIEXPORT void JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_releaseIntelligenceContext(JNIEnv *,jclass){std::lock_guard<std::mutex> lock(gate);release_context(intelligence_slot);}
extern "C" JNIEXPORT void JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_unloadIntelligence(JNIEnv *,jclass){unload_model(intelligence_slot);}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_generateIntelligence(JNIEnv *env,jclass,jbyteArray input,jlongArray timings,jint threads,jobject listener){return generate_model(env,input,timings,threads,intelligence_slot,false,listener,true);}

extern "C" JNIEXPORT void JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_configureFlow(JNIEnv *env,jclass,jboolean enabled,jintArray plan){
    std::lock_guard<std::mutex> lock(gate);
    auto &flow=intelligence_slot.flow;flow.reset(enabled==JNI_TRUE);
    if(!flow.enabled||!plan)return;
    jsize n=env->GetArrayLength(plan);if(n<0||n>64||n%2)return;
    jint data[64];env->GetIntArrayRegion(plan,0,n,data);if(env->ExceptionCheck())return;
    std::set<std::pair<int,int>> seen;
    for(int i=0;i<n;i+=2)if(data[i]>=0&&data[i]<128&&data[i+1]>=0&&data[i+1]<1024){
        std::pair<int,int> group{data[i],data[i+1]};
        if(seen.insert(group).second)flow.plan.push_back(group);
    }
}
extern "C" JNIEXPORT jstring JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_flowReport(JNIEnv *env,jclass){
    std::lock_guard<std::mutex> lock(gate);
    try{
        const auto &flow=intelligence_slot.flow;
        std::ostringstream out;out.imbue(std::locale::classic());out<<std::setprecision(9);
        out<<"v1\t"<<flow.advised<<"\t"<<flow.skipped<<"\n";
        for(const auto &entry:flow.values){
            const auto &key=entry.first;const auto &value=entry.second;
            if(!value.samples)continue;
            out<<key.phase<<'\t'<<key.family<<'\t'<<key.layer<<'\t'<<key.group<<'\t'<<value.sum/value.samples<<'\t'<<value.samples<<'\n';
        }
        return env->NewStringUTF(out.str().c_str());
    }catch(...){return env->NewStringUTF("");}
}
