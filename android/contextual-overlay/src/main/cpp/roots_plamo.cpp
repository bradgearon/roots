#include <jni.h>
#include <llama.h>
#include <ggml-backend.h>
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
struct model_slot {
    llama_model *model=nullptr;
    llama_context *resident_ctx=nullptr;
    ggml_backend_dev_t devices[2]={nullptr,nullptr};
    std::string backend="unloaded";
};
model_slot translation_slot, function_slot;
using clock_type=std::chrono::steady_clock;
int64_t elapsed_ms(clock_type::time_point start){return std::chrono::duration_cast<std::chrono::milliseconds>(clock_type::now()-start).count();}
void release_context(model_slot &slot){if(slot.resident_ctx){llama_free(slot.resident_ctx);slot.resident_ctx=nullptr;}}
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
        slot.backend=requested;
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
    slot.backend="unloaded";
}
static jbyteArray generate_model(JNIEnv *env,jbyteArray input,jlongArray timings,jint requested_threads,model_slot &slot,bool functions) {
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
        if(count<=0||count>(functions?1600:3000))throw std::runtime_error("Invalid prompt token count: "+std::to_string(count));
        std::vector<llama_token> tokens(count);
        count=llama_tokenize(vocab,prompt.data(),static_cast<int>(prompt.size()),tokens.data(),static_cast<int>(tokens.size()),true,true);
        if(count<=0||count>static_cast<int>(tokens.size()))throw std::runtime_error("Tokenization failed");
        stats[2]=elapsed_ms(start);stats[5]=count;
        stage="context setup";start=clock_type::now();
        if(slot.resident_ctx){
            llama_synchronize(slot.resident_ctx);
            auto memory=llama_get_memory(slot.resident_ctx);
            if(memory){llama_memory_clear(memory,true);stats[0]=1;}
            else release_context(slot);
        }
        if(!slot.resident_ctx){
            auto cp=llama_context_default_params();
            cp.n_ctx=functions?2048:4096;cp.n_batch=256;cp.n_ubatch=128;cp.n_seq_max=1;
            cp.n_threads=threads;cp.n_threads_batch=threads;
            slot.resident_ctx=llama_init_from_model(slot.model,cp);
            if(!slot.resident_ctx)throw std::runtime_error("Could not allocate context");
        }
        auto *ctx=slot.resident_ctx;
        llama_set_n_threads(ctx,threads,threads);
        stats[1]=elapsed_ms(start);stats[7]=llama_n_threads(ctx);
        stage="prompt decode";start=clock_type::now();
        for(int i=0;i<count;i+=256) {
            int rc=llama_decode(ctx,llama_batch_get_one(tokens.data()+i,std::min(256,count-i)));
            if(rc)throw std::runtime_error("llama_decode code "+std::to_string(rc));
        }
        llama_synchronize(ctx);stats[3]=elapsed_ms(start);
        stage="sampling";start=clock_type::now();
        sampler.reset(llama_sampler_init_greedy());
        if(!sampler)throw std::runtime_error("Could not allocate sampler");
        std::string out;
        const int max_output=functions?160:512;
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
            }else{
                auto stop=out.find("<|plamo:");
                if(stop!=std::string::npos){out.resize(stop);break;}
            }
            if(i==max_output-1)break;
            int rc=llama_decode(ctx,llama_batch_get_one(&token,1));
            if(rc)throw std::runtime_error("Generation decode code "+std::to_string(rc));
        }
        llama_synchronize(ctx);stats[4]=elapsed_ms(start);
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
