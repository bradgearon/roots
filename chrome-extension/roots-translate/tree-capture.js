(() => {
  "use strict";
  // Capture independently of translation. Only the foreground X document
  // contributes DOM observations; no network interception or hidden app state.
  const CARD='article[data-testid="tweet"], article[role="article"][data-testid="tweet"]';
  const BODY='[data-testid="tweetText"]';
  const HEADER='[data-testid="User-Name"]';
  const QUOTE='[data-testid="quoteTweet"], [data-testid="card.wrapper"], div[role="link"]';
  const PRIVATE='[role="dialog"], [contenteditable="true"], [data-testid="tweetTextarea_0"], [data-testid="DMDrawer"], [data-testid="messageEntry"]';
  const MAX_PENDING=64, MAX_SEEN=1024, RECOVERY_MS=2500;
  const seen=new Map(),pending=new Map(),confirmed=new Map();
  let enabled=false, busy=false, timer=null, checking=false, lastCheck=0;
  let captureEpoch=0;

  function canonical(raw) {
    try {
      const url=new URL(raw,location.origin);
      if(url.protocol!=="https:"||url.username||url.password||url.port||
          !/^(?:www\.)?(?:x\.com|twitter\.com)$/i.test(url.hostname))return "";
      if(!/^\/(?:[A-Za-z0-9_]{1,25}\/status|i\/(?:web\/)?status)\/[1-9][0-9]{0,24}\/?$/.test(url.pathname))return "";
      return "https://x.com"+url.pathname.replace(/\/$/,"");
    } catch {return "";}
  }
  function foreground() {
    return document.visibilityState==="visible"&&document.hasFocus()&&
      /^https:$/.test(location.protocol)&&/^(?:x\.com|twitter\.com)$/.test(location.hostname)&&
      !/^\/(?:messages|compose|settings|i\/flow|i\/chat)(?:\/|$)/i.test(location.pathname);
  }
  function displayed(node) {
    if(!node?.isConnected||node.closest(PRIVATE))return false;
    const rect=node.getBoundingClientRect();
    if(!rect.width||!rect.height)return false;
    const style=getComputedStyle(node);
    return style.visibility!=="hidden"&&style.display!=="none"&&style.opacity!=="0";
  }
  function visible(node) {
    if(!displayed(node))return false;
    const r=node.getBoundingClientRect();
    return r.bottom>0&&r.top<innerHeight&&r.right>0&&r.left<innerWidth;
  }
  function clean(value,max=4000) {
    let result=String(value??"").replace(/\0/g,"").trim();
    if(result.length>max){
      result=result.slice(0,max);
      if(/[\uD800-\uDBFF]$/.test(result))result=result.slice(0,-1);
    }
    return result;
  }
  function text(node,max=4000){return displayed(node)?clean(node.innerText,max):"";}
  function language(node){
    return (node?.getAttribute("lang")||"").toLowerCase().match(/^([a-z]{2,3})(?:[-_]|$)/)?.[1]||"";
  }
  function own(card,node){
    if(node.closest(CARD)!==card)return false;
    const quote=node.closest(QUOTE);
    return !quote||quote===card||!card.contains(quote);
  }
  function owned(card,selector){return [...card.querySelectorAll(selector)].filter(n=>own(card,n)&&displayed(n));}
  function profileHandle(anchor){
    try{
      const url=new URL(anchor.getAttribute("href"),location.origin);
      if(url.origin!==location.origin)return "";
      const match=url.pathname.match(/^\/([A-Za-z0-9_]{1,15})\/?$/);
      return match?"@"+match[1]:"";
    }catch{return "";}
  }
  function headerFields(card) {
    const headers=owned(card,HEADER);
    if(headers.length!==1)return {author:"",handle:"",url:"",time:""};
    const header=headers[0], links=[...header.querySelectorAll("a[href]")].filter(displayed);
    const handles=new Set();
    for(const anchor of links){
      const handle=profileHandle(anchor);
      if(handle&&text(anchor,64).toLowerCase()===handle.toLowerCase())handles.add(handle);
    }
    let handle=handles.size===1?[...handles][0]:"";
    const times=[...header.querySelectorAll("time")].filter(displayed);
    // A detail-page timestamp may be outside User-Name. Accept only one
    // owned timestamp with an actual status href, never an in-body URL.
    if(!times.length)times.push(...owned(card,"time"));
    const candidates=new Map();
    for(const t of times){
      const a=t.closest("a[href]");if(!a||!own(card,a))continue;
      const url=canonical(a.getAttribute("href"));if(!url)continue;
      const owner=url.split("/")[3];
      if(handle&&owner!=="i"&&owner.toLowerCase()!==handle.slice(1).toLowerCase())continue;
      candidates.set(url,{url,time:t.getAttribute("datetime")||text(t,80),owner});
    }
    const source=candidates.size===1?[...candidates.values()][0]:null;
    if(!handle&&source&&source.owner!=="i")handle="@"+source.owner;
    let author="";
    if(handle){
      for(const a of links){
        const value=text(a,240);
        if(profileHandle(a).toLowerCase()===handle.toLowerCase()&&value&&value.toLowerCase()!==handle.toLowerCase()){
          author=value;break;
        }
      }
    }
    return {author,handle,url:source?.url||"",time:clean(source?.time,80)};
  }
  function metric(card,selector){
    const control=owned(card,selector)[0];
    if(!control)return "";
    // Preserve displayed counts only. Never parse a count out of body prose.
    const value=text(control,48);
    return /^[0-9][0-9.,\s]*(?:[kmb万億])?$/i.test(value)?value:"";
  }
  function unique(values,limit){
    return [...new Set(values.filter(Boolean))].slice(0,limit);
  }
  function read(card,requiredBody=null){
    if(!foreground()||!visible(card)||card.closest(PRIVATE))return null;
    const bodies=owned(card,BODY);
    // If ownership is ambiguous, do not attach a quoted body to its parent.
    if(bodies.length!==1)return null;
    const body=bodies[0];
    if(requiredBody&&requiredBody!==body)return null;
    const value=text(body,12001);
    if(!value||value.length>12000)return null;
    const header=headerFields(card);
    const media=unique(owned(card,'[data-testid="tweetPhoto"] img').map(img=>clean(img.alt,1000)),8);
    const context=unique([...card.querySelectorAll(BODY)].filter(n=>n!==body&&displayed(n)).map(n=>text(n,2000)),8);
    const poll=unique(owned(card,'[data-testid="pollOption"], [data-testid="pollOptionText"]').map(n=>text(n,300)),8);
    return {text:value,...header,language:language(body),replyCount:metric(card,'[data-testid="reply"]'),
      repostCount:metric(card,'[data-testid="retweet"], [data-testid="unretweet"]'),
      likeCount:metric(card,'[data-testid="like"], [data-testid="unlike"]'),
      viewCount:metric(card,'a[href$="/analytics"], [data-testid="viewCount"]'),media,context,poll};
  }
  function fingerprint(post){return JSON.stringify(post);}
  function remember(map,key,value,limit){
    map.delete(key);map.set(key,value);
    while(map.size>limit)map.delete(map.keys().next().value);
  }
  function enqueue(post){
    if(!enabled||!post)return;
    const key=fingerprint(post);
    if(seen.has(key)||pending.has(key))return;
    pending.set(key,post);
    while(pending.size>MAX_PENDING)pending.delete(pending.keys().next().value);
    drain();
  }
  async function message(request){
    try{return await chrome.runtime.sendMessage(request);}
    catch{return null;}
  }
  async function drain(){
    if(busy||!enabled||!foreground())return;
    busy=true;
    const epoch=captureEpoch;
    try{
      while(enabled&&foreground()&&epoch===captureEpoch&&pending.size){
        const [key,post]=pending.entries().next().value;
        const answer=await message({type:"rootsTreeObserve",post});
        if(epoch!==captureEpoch)break;
        if(answer?.ok){
          pending.delete(key);remember(seen,key,true,MAX_SEEN);
        }else{
          // Keep a bounded volatile queue across a transient host failure.
          // A disabled capture setting drops the queue immediately.
          if(answer?.error==="capture_off"){setEnabled(false);}
          break;
        }
      }
    }finally{busy=false;}
  }
  function setEnabled(value){
    if(enabled===value)return;
    enabled=value;captureEpoch++;
    if(!value){pending.clear();seen.clear();confirmed.clear();}
  }
  async function check(){
    if(checking||!foreground()||Date.now()-lastCheck<RECOVERY_MS)return;
    checking=true;lastCheck=Date.now();
    try{
      const state=await message({type:"rootsTreeSettings"});
      if(state?.ok)setEnabled(state.capture===true);
      else if(state?.error==="capture_off")setEnabled(false);
    }finally{checking=false;}
  }
  async function scan(){
    timer=null;
    if(!foreground())return;
    await check();
    if(!enabled||!foreground())return;
    // Inspect only the rendered X article nodes, not private dialogs or
    // unrelated page text. DOM identity is re-read on every snapshot.
    for(const card of [...document.querySelectorAll(CARD)].filter(visible).slice(0,24)){
      if(card.parentElement?.closest(CARD))continue;
      const post=read(card);
      if(!post)continue;
      enqueue(post);
      const key=post.handle+"\u001f"+post.text;
      const localized=confirmed.get(key);
      if(localized)enqueue({...post,...localized});
    }
    drain();
  }
  function schedule(delay=160){
    if(timer!==null||!foreground())return;
    timer=setTimeout(scan,delay);
  }
  // Save the exact original snapshot at translation admission. A recycled
  // node or a later quote/header change cannot bind a result to another post.
  globalThis.RootsTreeCapture={
    snapshot(node){
      if(!enabled||!foreground())return null;
      const card=node?.closest(CARD);return card?read(card,node):null;
    },
    translated(post,response,target){
      if(!enabled||!post||!response?.ok)return;
      const code=String(target||"").toLowerCase();
      if(!/^[a-z]{2,3}$/.test(code))return;
      const detected=response.detection;
      let fields=null;
      if(response.skipped&&detected?.reliable&&detected.language===code)
        fields={language:code,translation:post.text,target:code,localized:true};
      else if(typeof response.translation==="string"&&response.translation.trim())
        fields={language:response.sourceLang||post.language,translation:clean(response.translation,12000),target:response.targetLang||code,localized:false};
      if(!fields)return;
      remember(confirmed,post.handle+"\u001f"+post.text,fields,256);
      enqueue({...post,...fields});
    }
  };
  new MutationObserver(records=>{
    for(const record of records){
      const node=record.target instanceof Element?record.target:record.target.parentElement;
      if(node?.closest(".roots-local-actions"))continue;
      schedule();break;
    }
  }).observe(document.documentElement,{childList:true,subtree:true,characterData:true,
    attributes:true,attributeFilter:["href","datetime","lang","data-testid","aria-label"]});
  addEventListener("scroll",()=>schedule(120),{passive:true,capture:true});
  addEventListener("focus",()=>{lastCheck=0;schedule(0);});
  document.addEventListener("visibilitychange",()=>{if(foreground()){lastCheck=0;schedule(0);}});
  // Recovery is limited to the active page and catches recycled DOM/SPA
  // updates, or a host that was launched after the page had already loaded.
  setInterval(()=>{if(foreground())schedule(0);},RECOVERY_MS);
  schedule(0);
})();
