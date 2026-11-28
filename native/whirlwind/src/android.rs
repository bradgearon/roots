use super::*;
use jni::{JNIEnv,objects::{JClass,JObject,JString,JByteArray,GlobalRef},sys::jstring};
use std::sync::{OnceLock,Mutex};
static RUNTIME:OnceLock<tokio::runtime::Runtime>=OnceLock::new();
static CONTEXT:OnceLock<GlobalRef>=OnceLock::new();
static INIT_LOCK:Mutex<()>=Mutex::new(());
static ACTIVE:Mutex<Option<Tunnel>>=Mutex::new(None);
fn runtime()->&'static tokio::runtime::Runtime{RUNTIME.get_or_init(||tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().expect("runtime"))}
fn seed(env:&JNIEnv,bytes:&JByteArray)->Result<[u8;32]>{
    env.convert_byte_array(bytes)?.try_into().map_err(|_|anyhow!("invalid key"))
}
fn init(env:&JNIEnv,context:&JObject)->Result<()>{
    let _lock=INIT_LOCK.lock().map_err(|_|anyhow!("context lock"))?;
    if CONTEXT.get().is_none(){
        let global=env.new_global_ref(context)?;
        let vm=env.get_java_vm()?;
        unsafe{iroh::dns::install_android_jni_context(vm.get_java_vm_pointer().cast(),global.as_obj().as_raw().cast())};
        CONTEXT.set(global).map_err(|_|anyhow!("context already initialized"))?;
    }
    Ok(())
}
fn answer(env:&mut JNIEnv,result:Result<String>)->jstring{
    // No dependency or system errors containing routing information or key material.
    match result{
        Ok(s)=>env.new_string(s).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(_)=>{let _=env.throw_new("java/io/IOException","Whirlwind native operation failed; check device grants, relay connectivity and native build");std::ptr::null_mut()}
    }
}
#[no_mangle]
pub extern "system" fn Java_com_wds_sixstars_contextoverlay_DesktopWhirlwind_nativeIdentity(mut env:JNIEnv,_:JClass,bytes:JByteArray)->jstring{
    let result=(||{let mut s=seed(&env,&bytes)?;let result=identities(&s);s.zeroize();let (a,b)=result?;Ok(format!("iroh {a}\nlibp2p {b}"))})();
    answer(&mut env,result)
}
#[no_mangle]
pub extern "system" fn Java_com_wds_sixstars_contextoverlay_DesktopWhirlwind_nativeOpen(mut env:JNIEnv,_:JClass,context:JObject,bytes:JByteArray,target:JString,relay:JString,fallback:JString)->jstring{
    let result=(||{
        init(&env,&context)?;
        let target:String=env.get_string(&target)?.into();
        let relay:String=env.get_string(&relay)?.into();
        let fallback:String=env.get_string(&fallback)?.into();
        let mut s=seed(&env,&bytes)?;
        let result=runtime().block_on(tunnel(&s,&target,&relay,&fallback));s.zeroize();
        let t=result?;
        let result=format!("{}:{}",t.port,t.transport);
        let mut active=ACTIVE.lock().map_err(|_|anyhow!("tunnel lock"))?;
        if let Some(old)=active.take(){old.task.abort();}
        *active=Some(t);Ok(result)
    })();
    answer(&mut env,result)
}
#[no_mangle]
pub extern "system" fn Java_com_wds_sixstars_contextoverlay_DesktopWhirlwind_nativeClose(_env:JNIEnv,_:JClass){
    if let Ok(mut active)=ACTIVE.lock(){if let Some(t)=active.take(){t.task.abort();}}
}

#[no_mangle]
pub extern "system" fn Java_com_wds_sixstars_contextoverlay_ExternalDirectory_nativeSearch(mut env:JNIEnv,_:JClass,context:JObject,bytes:JByteArray,seeds:JString,query:JString)->jstring{
    let result:Result<String>=(||{
        init(&env,&context)?;
        let seeds:String=env.get_string(&seeds)?.into();
        let query:String=env.get_string(&query)?.into();
        let routes=seeds.lines().filter(|s|!s.trim().is_empty()).map(|s|s.trim().parse()).collect::<Result<Vec<Multiaddr>,_>>()?;
        let mut s=seed(&env,&bytes)?;
        let result=runtime().block_on(directory::search(&s,routes,&query));s.zeroize();
        Ok(serde_json::to_string(&result?)?)
    })();
    match result{
        Ok(s)=>env.new_string(s).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(_)=>{let _=env.throw_new("java/io/IOException","External directory search failed; check bootstrap address, query tag and connectivity");std::ptr::null_mut()}
    }
}
