/* Visible, foreground webpage text only. Never collect field values, hidden
 * DOM, screenshots, links, cookies, selections, or cross-origin frames. */
(() => {
  "use strict";
  if(window.top!==window||globalThis.__rootsExperienceDom)return;
  globalThis.__rootsExperienceDom=true;
  const MAX_LINES=80, MAX_CHARS=16000, MAX_NODES=3000;
  const excluded="input,textarea,select,option,button,form,script,style,noscript,template,[contenteditable]:not([contenteditable='false']),[role='textbox'],[role='combobox'],[aria-hidden='true'],[hidden],[inert],[data-roots-private]";
  let lastSignature="",lastSent=0,busy=false;
  const documentKey=crypto.randomUUID();
  function eligible(el){
    if(!el||el.closest(excluded)||el.isContentEditable)return false;
    for(let p=el;p;p=p.parentElement){
      const s=getComputedStyle(p);
      if(s.display==="none"||s.visibility!=="visible"||Number(s.opacity)===0||
         s.contentVisibility==="hidden"||p.hidden)return false;
    }
    return true;
  }
  function visibleRect(r,el){
    if(r.width<=0||r.height<=0||r.left<0||r.top<0||
       r.right>innerWidth||r.bottom>innerHeight)return false;
    // Avoid reading behind overlays or clipped containers. All tested points
    // must hit the element or its descendants in the currently rendered page.
    for(const [x,y] of [[r.left+r.width/2,r.top+r.height/2],
      [r.left+Math.min(1,r.width/2),r.top+r.height/2],
      [r.right-Math.min(1,r.width/2),r.top+r.height/2]]){
      const hit=document.elementFromPoint(x,y);
      if(!hit||!(hit===el||el.contains(hit)))return false;
    }
    return true;
  }
  function boundsFor(range,el){
    const rects=[...range.getClientRects()];
    if(!rects.length||rects.length>64||!rects.every(r=>visibleRect(r,el)))return null;
    return [Math.floor(Math.min(...rects.map(r=>r.left))),
      Math.floor(Math.min(...rects.map(r=>r.top))),
      Math.ceil(Math.max(...rects.map(r=>r.right))),
      Math.ceil(Math.max(...rects.map(r=>r.bottom)))];
  }
  function collect(){
    if(!document.body||document.visibilityState!=="visible"||!document.hasFocus()||document.designMode==="on")return [];
    const walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
    const rows=[];let chars=0,visited=0;
    const deadline=performance.now()+120;
    const add=(text,bounds)=>{
      text=text.replace(/\s+/gu," ").trim();
      if(!text||text.length>1800||chars+text.length>MAX_CHARS||rows.length>=MAX_LINES)return;
      chars+=text.length;rows.push({text,bounds});
    };
    for(let n=walker.nextNode();n&&visited++<MAX_NODES&&performance.now()<deadline&&rows.length<MAX_LINES;n=walker.nextNode()){
      const text=n.nodeValue||"",el=n.parentElement;
      if(!text.trim()||!eligible(el))continue;
      const coarse=el.getBoundingClientRect();
      if(coarse.right<=0||coarse.bottom<=0||coarse.left>=innerWidth||coarse.top>=innerHeight)continue;
      const range=document.createRange();
      range.selectNodeContents(n);
      let rect=boundsFor(range,el);
      if(rect&&text.length<=1800){add(text,rect);continue;}
      // A paragraph crossing the viewport boundary is not read wholesale.
      // Retain only words/segments whose own rendered ranges are visible.
      const segmenter=new Intl.Segmenter(undefined,{granularity:"word"});
      let value="",combined=null,previousBottom=null;
      function flush(){if(combined)add(value,combined);value="";combined=null;previousBottom=null;}
      for(const part of segmenter.segment(text.slice(0,16000))){
        if(performance.now()>=deadline||rows.length>=MAX_LINES)break;
        range.setStart(n,part.index);range.setEnd(n,part.index+part.segment.length);
        rect=boundsFor(range,el);
        if(!rect){flush();continue;}
        if(previousBottom!==null&&Math.abs(rect[3]-previousBottom)>8)flush();
        if(value.length+part.segment.length>1800)flush();
        value+=part.segment;
        combined=combined?[Math.min(combined[0],rect[0]),Math.min(combined[1],rect[1]),Math.max(combined[2],rect[2]),Math.max(combined[3],rect[3])]:rect;
        previousBottom=rect[3];
      }
      flush();
    }
    return rows.sort((a,b)=>a.bounds[1]-b.bounds[1]||a.bounds[0]-b.bounds[0]);
  }
  async function tick(){
    if(busy||document.visibilityState!=="visible"||!document.hasFocus())return;
    busy=true;
    try{
      const state=await chrome.runtime.sendMessage({type:"rootsExperienceSettings"});
      if(!state?.ok||!state.capture){lastSignature="";return;}
      const rows=collect();
      if(!rows.length)return;
      // Position-independent fingerprint avoids repeatedly recording a static
      // webpage merely because layout moved a few pixels.
      const signature=JSON.stringify(rows.map(r=>r.text));
      if(signature===lastSignature&&Date.now()-lastSent<60000)return;
      if(document.visibilityState!=="visible"||!document.hasFocus())return;
      const result=await chrome.runtime.sendMessage({type:"rootsExperienceObserve",
        snapshot:{documentKey,host:location.hostname,rows}});
      if(result?.ok){lastSignature=signature;lastSent=Date.now();}
    }catch{/* Extension reload or host unavailable: retry on the next tick. */}
    finally{busy=false;}
  }
  document.addEventListener("visibilitychange",()=>{if(document.hidden)lastSignature="";else void tick();});
  window.addEventListener("focus",()=>{lastSignature="";void tick();});
  setInterval(()=>void tick(),3000);
  void tick();
})();
