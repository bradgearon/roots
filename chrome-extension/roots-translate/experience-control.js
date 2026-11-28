// Shared control on trusted extension popup and Tree page. The host's persisted
// state is authoritative; closing this page never changes capture.
(() => {
  "use strict";
  const button=document.getElementById("experienceCaptureToggle");
  const label=document.getElementById("experienceCaptureStatus");
  if(!button||!label)return;
  let state=null,busy=false,reading=false,generation=0;
  async function send(operation,values={}){
    const result=await chrome.runtime.sendMessage({type:"rootsTreeCommand",operation,...values});
    if(!result?.ok||!result.state)throw new Error(result?.error||"Host unavailable");
    return result.state;
  }
  function render(s){
    state=s;
    button.textContent=s.enabled?"Pause Experience capture":"Resume Experience capture";
    button.disabled=busy;
    if(s.watcherRunning&&!s.supportsPause){
      label.textContent="Older Surface watcher: stop it with Ctrl+C and restart the updated watcher. Setting is saved, but pause is not verified.";
    }else if(!s.enabled){
      label.textContent=s.workerActive?"Pausing — new writes blocked; stopping native reader…":"Paused — desktop and webpage capture off.";
    }else if(!s.watcherRunning){
      label.textContent="Enabled, watcher stopped. Run launch-surface.cmd to capture.";
    }else if(s.workerActive&&s.currentEpoch){
      label.textContent="Active — desktop capture enabled.";
    }else{
      label.textContent="Starting Experience capture…";
    }
  }
  function unavailable(){
    state=null;button.textContent="Pause Experience capture";button.disabled=busy;
    label.textContent="Capture status unavailable. Use launch-surface.cmd --pause, or Ctrl+C in the Surface console.";
  }
  async function refresh(){
    if(busy||reading)return;
    reading=true;const version=++generation;
    try{
      const result=await send("experienceCaptureStatus");
      if(version===generation&&!busy)render(result);
    }catch{
      if(version===generation&&!busy)unavailable();
    }finally{reading=false;}
  }
  button.addEventListener("click",async()=>{
    if(busy)return;
    const enabled=state?.enabled===false;
    busy=true;++generation;button.disabled=true;
    label.textContent=enabled?"Resuming…":"Pausing…";
    try{
      const result=await send("experienceCaptureSet",{enabled});
      busy=false;render(result);
    }catch{busy=false;unavailable();}
    finally{void refresh();}
  });
  void refresh();
  document.addEventListener("visibilitychange",()=>{if(!document.hidden)void refresh();});
  window.addEventListener("focus",()=>void refresh());
  setInterval(()=>{if(!document.hidden)void refresh();},1500);
})();
