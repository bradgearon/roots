/* Visible, foreground webpage text only. Never collect field values, hidden
 * DOM, screenshots, links, cookies, selections, or cross-origin frames. */
(() => {
  "use strict";
  if(window.top!==window||globalThis.__rootsExperienceDom)return;
  globalThis.__rootsExperienceDom=true;
  const MAX_LINES=80, MAX_CHARS=16000, MAX_NODES=3000;
  const excluded="input,textarea,select,option,button,form,script,style,noscript,template,[contenteditable]:not([contenteditable='false']),[role='textbox'],[role='combobox'],[aria-hidden='true'],[hidden],[inert],[data-roots-private]";
  let lastSignature="",busy=false,scrollTimer=0;
  let nextAnchor=0;
  const anchors=new WeakMap();
  const positionLimit=1000000000;
  function anchorFor(node){if(!anchors.has(node))anchors.set(node,++nextAnchor);return anchors.get(node);}
  function pagePosition(el,bounds){
    let x=window.scrollX,y=window.scrollY;
    for(let p=el.parentElement;p&&p!==document.documentElement&&p!==document.body;p=p.parentElement){x+=p.scrollLeft;y+=p.scrollTop;}
    const bounded=n=>Math.max(-positionLimit,Math.min(positionLimit,Math.round(n)));
    return [bounded(bounds[0]+x),bounded(bounds[1]+y)];
  }
  const documentKey=crypto.randomUUID?.()??Array.from(crypto.getRandomValues(new Uint8Array(16)),b=>b.toString(16).padStart(2,"0")).join("").replace(/^(........)(....)(....)(....)(............)$/,"$1-$2-$3-$4-$5");
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
    const walker=document.createTreeWalker(document.body,NodeFilter.SHOW_ELEMENT|NodeFilter.SHOW_TEXT,{
      acceptNode(n){
        if(n.nodeType===Node.TEXT_NODE)return NodeFilter.FILTER_ACCEPT;
        if(n.matches(excluded)||n.isContentEditable)return NodeFilter.FILTER_REJECT;
        const style=getComputedStyle(n);
        if(style.display==="none"||style.visibility==="hidden"||Number(style.opacity)===0||style.contentVisibility==="hidden")
          return NodeFilter.FILTER_REJECT;
        const r=n.getBoundingClientRect();
        // Skip offscreen ordinary layout blocks. Keep display:contents and
        // positioned/overflow-visible containers whose descendants may be on screen.
        if((r.bottom<=0||r.top>=innerHeight||r.right<=0||r.left>=innerWidth)&&
           r.width>0&&r.height>0&&style.display!=="contents"&&
           ((style.overflowX!=="visible"&&style.overflowY!=="visible")||
            (style.position==="static"&&!n.querySelector(":scope > [style*='position']"))))
          return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_SKIP;
      }
    });
    const rows=[];let chars=0,visited=0;
    const deadline=performance.now()+180;
    const segmenter=new Intl.Segmenter(undefined,{granularity:"word"});
    const add=(text,bounds,n,start,end)=>{
      text=text.replace(/\s+/gu," ").trim();
      if(!text||text.length>1800||chars+text.length>MAX_CHARS||rows.length>=MAX_LINES)return;
      const [pageX,pageY]=pagePosition(n.parentElement,bounds);
      chars+=text.length;rows.push({text,bounds,anchor:anchorFor(n),start,end,pageX,pageY,order:visited});
    };
    for(let n=walker.nextNode();n&&visited++<MAX_NODES&&performance.now()<deadline&&rows.length<MAX_LINES;n=walker.nextNode()){
      const text=n.nodeValue||"",el=n.parentElement;
      if(!text.trim()||!eligible(el))continue;
      const coarse=el.getBoundingClientRect();
      if(coarse.right<=0||coarse.bottom<=0||coarse.left>=innerWidth||coarse.top>=innerHeight)continue;
      const range=document.createRange();
      range.selectNodeContents(n);
      const allRects=[...range.getClientRects()];
      // A single visual line has the same identity whether it was first
      // encountered at the top, middle or bottom of the viewport.
      if(allRects.length===1&&text.length<=1800){
        const rect=boundsFor(range,el);if(rect)add(text,rect,n,0,text.length);continue;
      }
      // Always use rendered lines for multiline nodes, rather than switching
      // between a paragraph and its clipped words as the user scrolls.
      let lineStart=-1,lineEnd=0,lineRect=null,lineBottom=null,lineVisible=true,interrupted=false;
      function flush(){
        if(lineRect&&lineVisible&&lineStart>=0)
          add(text.slice(lineStart,lineEnd),lineRect,n,lineStart,lineEnd);
        lineStart=-1;lineEnd=0;lineRect=null;lineBottom=null;lineVisible=true;
      }
      for(const part of segmenter.segment(text.slice(0,16000))){
        if(performance.now()>=deadline||rows.length>=MAX_LINES){interrupted=true;break;}
        range.setStart(n,part.index);range.setEnd(n,part.index+part.segment.length);
        const rects=[...range.getClientRects()].filter(r=>r.width>0&&r.height>0);
        if(!rects.length)continue;
        // Do not retain words split across an occluded or clipped boundary.
        const r=rects[0];
        if(lineBottom!==null&&Math.abs(r.bottom-lineBottom)>5)flush();
        if(lineStart>=0&&part.index+part.segment.length-lineStart>1800)flush();
        if(lineStart<0)lineStart=part.index;
        lineEnd=part.index+part.segment.length;
        lineBottom=r.bottom;
        const bounds=boundsFor(range,el);
        if(!bounds){lineVisible=false;continue;}
        lineRect=lineRect?[Math.min(lineRect[0],bounds[0]),Math.min(lineRect[1],bounds[1]),Math.max(lineRect[2],bounds[2]),Math.max(lineRect[3],bounds[3])]:bounds;
      }
      if(!interrupted)flush();
    }
    return rows.sort((a,b)=>a.pageY-b.pageY||a.pageX-b.pageX||a.order-b.order||a.start-b.start);
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
      const signature=JSON.stringify(rows.map(r=>[r.anchor,r.start,r.end,r.text,r.pageX,r.pageY]));
      if(signature===lastSignature)return;
      if(document.visibilityState!=="visible"||!document.hasFocus())return;
      const result=await chrome.runtime.sendMessage({type:"rootsExperienceObserve",
        snapshot:{documentKey,host:location.hostname,rows}});
      if(result?.ok)lastSignature=signature;
    }catch{/* Extension reload or host unavailable: retry on the next tick. */}
    finally{busy=false;}
  }
  document.addEventListener("visibilitychange",()=>{if(document.hidden)lastSignature="";else void tick();});
  window.addEventListener("focus",()=>{lastSignature="";void tick();});
  // Observe scroll settling (including nested scroll containers), not just a
  // fixed poll that might miss a short-lived viewport. The host still bounds rate.
  document.addEventListener("scroll",()=>{clearTimeout(scrollTimer);scrollTimer=setTimeout(()=>void tick(),300);}, {capture:true,passive:true});
  setInterval(()=>void tick(),1500);
  void tick();
})();
