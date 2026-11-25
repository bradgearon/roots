#include <jni.h>
#include <llama.h>
#include <string>
#include <vector>
#include <stdexcept>
#include <algorithm>
#include <mutex>
static std::mutex gate;
static llama_model *model=nullptr;
static bool backend=false;
static void fail(JNIEnv *e,const char *s){e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),s);}
extern "C" JNIEXPORT void JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_load(JNIEnv *e,jclass,jstring path){
 std::lock_guard<std::mutex> lock(gate);const char *p=e->GetStringUTFChars(path,nullptr); if(!p)return;
 if(!backend){llama_backend_init();backend=true;}
 auto mp=llama_model_default_params();mp.n_gpu_layers=0;
 auto *next=llama_model_load_from_file(p,mp);e->ReleaseStringUTFChars(path,p);
 if(!next){fail(e,"Model load failed");return;}if(model)llama_model_free(model);model=next;
}
extern "C" JNIEXPORT void JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_unload(JNIEnv *,jclass){
 std::lock_guard<std::mutex> lock(gate);if(model){llama_model_free(model);model=nullptr;}
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_wds_sixstars_contextoverlay_LocalPLaMoTranslationProvider_00024Native_generate(JNIEnv *e,jclass,jbyteArray input){
 std::lock_guard<std::mutex> lock(gate);llama_context *ctx=nullptr;llama_sampler *sampler=nullptr;
 try {
  if(!model)throw std::runtime_error("Model not loaded");
  std::string prompt(e->GetArrayLength(input),'\0');e->GetByteArrayRegion(input,0,prompt.size(),reinterpret_cast<jbyte*>(prompt.data()));
  if(e->ExceptionCheck())return nullptr;
  auto *vocab=llama_model_get_vocab(model);
  int count=-llama_tokenize(vocab,prompt.data(),prompt.size(),nullptr,0,true,true);
  if(count<=0 || count>3000)throw std::runtime_error("Prompt too long");
  std::vector<llama_token> tokens(count);
  count=llama_tokenize(vocab,prompt.data(),prompt.size(),tokens.data(),tokens.size(),true,true);
  if(count<=0)throw std::runtime_error("Tokenization failed");
  auto cp=llama_context_default_params();cp.n_ctx=4096;cp.n_batch=512;cp.n_threads=4;cp.n_threads_batch=4;
  ctx=llama_init_from_model(model,cp);if(!ctx)throw std::runtime_error("Context allocation failed");
  for(int i=0;i<count;i+=512)if(llama_decode(ctx,llama_batch_get_one(tokens.data()+i,std::min(512,count-i))))throw std::runtime_error("Prompt decode failed");
  sampler=llama_sampler_init_greedy();std::string out;
  for(int i=0;i<512;i++){
   auto token=llama_sampler_sample(sampler,ctx,-1);if(llama_vocab_is_eog(vocab,token))break;
   char buf[512];int n=llama_token_to_piece(vocab,token,buf,sizeof(buf),0,true);
   if(n<0)throw std::runtime_error("Token too large");out.append(buf,n);
   auto stop=out.find("<|plamo:");if(stop!=std::string::npos){out.resize(stop);break;}
   if(llama_decode(ctx,llama_batch_get_one(&token,1)))throw std::runtime_error("Generation failed");
  }
  llama_sampler_free(sampler);sampler=nullptr;llama_free(ctx);ctx=nullptr;
  jbyteArray result=e->NewByteArray(out.size());if(result)e->SetByteArrayRegion(result,0,out.size(),reinterpret_cast<const jbyte*>(out.data()));return result;
 }catch(const std::exception &x){if(sampler)llama_sampler_free(sampler);if(ctx)llama_free(ctx);fail(e,x.what());return nullptr;}
}
