#include <jni.h>
#include <llama.h>
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
llama_model *model=nullptr;
llama_context *resident_ctx=nullptr;
using clock_type=std::chrono::steady_clock;
int64_t elapsed_ms(clock_type::time_point start){return std::chrono::duration_cast<std::chrono::milliseconds>(clock_type::now()-start).count();}
void release_context(){if(resident_ctx){llama_free(resident_ctx);resident_ctx=nullptr;}}
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
extern "C" JNIEXPORT void JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_load(JNIEnv *env,jclass,jstring path) {
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
        auto mp=llama_model_default_params();mp.n_gpu_layers=0;
        // Release an old model before allocating another one on a mobile device.
        release_context();
        if(model){llama_model_free(model);model=nullptr;}
        model=llama_model_load_from_file(filename.c_str(),mp);
        if(!model)throw std::runtime_error("llama_model_load_from_file returned null"+load_detail());
        __android_log_write(ANDROID_LOG_INFO,"RootsPLaMo","Model loaded: " ROOTS_PLAMO_LLAMA_REVISION);
    }catch(const std::exception &ex) {
        if(p)env->ReleaseStringUTFChars(path,p);
        fail(env,stage+" [" ROOTS_PLAMO_LLAMA_REVISION "]: "+ex.what());
    }catch(...) {
        if(p)env->ReleaseStringUTFChars(path,p);
        fail(env,stage+" [" ROOTS_PLAMO_LLAMA_REVISION "]: unknown native failure");
    }
}
extern "C" JNIEXPORT void JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_unload(JNIEnv *,jclass) {
    std::lock_guard<std::mutex> lock(gate);
    release_context();
    if(model){llama_model_free(model);model=nullptr;}
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_generate(JNIEnv *env,jclass,jbyteArray input,jlongArray timings) {
    std::lock_guard<std::mutex> lock(gate);
    // Context and model are shared by the one serialized native worker.
    // Reuse allocations, never retain a previous request's attention state.
    jlong stats[8]={}; // reused, setup_ms, tokenize_ms, prefill_ms, generation_ms, prompt_tokens, sampled_tokens, threads
    std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)> sampler(nullptr,llama_sampler_free);
    std::string stage="tokenization";
    try {
        if(!model)throw std::runtime_error("Model not loaded");
        if(!input)throw std::runtime_error("Missing prompt");
        if(!timings||env->GetArrayLength(timings)!=8)throw std::runtime_error("Missing timing buffer");
        auto start=clock_type::now();
        std::string prompt(env->GetArrayLength(input),'\0');
        env->GetByteArrayRegion(input,0,static_cast<jsize>(prompt.size()),reinterpret_cast<jbyte*>(prompt.data()));
        if(env->ExceptionCheck())return nullptr;
        auto *vocab=llama_model_get_vocab(model);
        int count=-llama_tokenize(vocab,prompt.data(),static_cast<int>(prompt.size()),nullptr,0,true,true);
        if(count<=0||count>3000)throw std::runtime_error("Invalid prompt token count: "+std::to_string(count));
        std::vector<llama_token> tokens(count);
        count=llama_tokenize(vocab,prompt.data(),static_cast<int>(prompt.size()),tokens.data(),static_cast<int>(tokens.size()),true,true);
        if(count<=0||count>static_cast<int>(tokens.size()))throw std::runtime_error("Tokenization failed");
        stats[2]=elapsed_ms(start);stats[5]=count;
        stage="context setup";start=clock_type::now();
        if(resident_ctx){
            llama_synchronize(resident_ctx);
            auto memory=llama_get_memory(resident_ctx);
            if(memory){llama_memory_clear(memory,true);stats[0]=1;}
            else release_context();
        }
        if(!resident_ctx){
            auto cp=llama_context_default_params();
            cp.n_ctx=4096;cp.n_batch=256;cp.n_ubatch=128;cp.n_seq_max=1;
            cp.n_threads=4;cp.n_threads_batch=4;
            resident_ctx=llama_init_from_model(model,cp);
            if(!resident_ctx)throw std::runtime_error("Could not allocate context");
        }
        auto *ctx=resident_ctx;
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
        for(int i=0;i<512;i++) {
            auto token=llama_sampler_sample(sampler.get(),ctx,-1);
            ++stats[6];
            if(llama_vocab_is_eog(vocab,token))break;
            std::vector<char> piece(512);
            int n=llama_token_to_piece(vocab,token,piece.data(),static_cast<int>(piece.size()),0,true);
            if(n<0&&n>=-16384){
                piece.resize(static_cast<size_t>(-n));
                n=llama_token_to_piece(vocab,token,piece.data(),static_cast<int>(piece.size()),0,true);
            }
            if(n<0||n>static_cast<int>(piece.size()))throw std::runtime_error("Token piece conversion failed");
            out.append(piece.data(),n);
            auto stop=out.find("<|plamo:");
            if(stop!=std::string::npos){out.resize(stop);break;}
            if(i==511)break;
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
    }catch(const std::exception &ex){release_context();fail(env,stage+" [" ROOTS_PLAMO_LLAMA_REVISION "]: "+ex.what());return nullptr;}
    catch(...){release_context();fail(env,stage+" [" ROOTS_PLAMO_LLAMA_REVISION "]: unknown native failure");return nullptr;}
}
