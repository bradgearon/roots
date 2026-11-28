"use strict";
const el=id=>document.getElementById(id);
const status=value=>{el("message").textContent=value;};
let latest=null,beforeMs=0,beforeSequence=0,loading=false,refreshing=false,generation=0,query="",mode="records";
let timelineDay="",screenPageSize=12;
let selectedSource="knowledge",selectedAgent=0,pages=0,viewRevision=null,viewRefreshing=false,viewEpoch=0;
let invitations=[],inviteSignature="",peerSignature="",branchSignature="";
// This code runs only in the trusted extension Tree page. A previously
// loaded MV3 worker may not recognize newly added view commands. On that
// explicit rejection, use the same token-authenticated loopback routes.
// Never fall back after an ambiguous timeout or failed write.
async function localViewCommand(operation,args){
  if(location.href.split(/[?#]/)[0]!==chrome.runtime.getURL("tree.html"))return {ok:false,error:"untrusted_sender"};
  const source=args.source;
  if(source!=="experience"&&source!=="knowledge")return {ok:false,error:"invalid_source"};
  let path="/view/"+source,method="GET",body,timeout=10000;
  const positive=v=>Number.isSafeInteger(v)&&v>0?v:0;
  switch(operation){
    case "viewStatus":path+="/status";break;
    case "viewRecords":case "viewFeed":case "viewScreens":{
      if(operation==="viewScreens"&&source!=="experience")return {ok:false,error:"invalid_source"};
      const params=new URLSearchParams({term:String(args.term||"").slice(0,512),
        beforeMs:String(positive(args.beforeMs)),beforeSequence:String(positive(args.beforeSequence)),
        agent:String(positive(args.agent)),limit:operation==="viewScreens"?"12":"30"});
      path+=(operation==="viewScreens"?"/screens":operation==="viewRecords"?"/records":"/feed")+"?"+params;break;
    }
    case "viewWeights":
      if(!positive(args.sequence))return {ok:false,error:"invalid_sequence"};
      path+="/weights/"+args.sequence;break;
    case "viewBranches":path+="/branches";break;
    case "viewAddBranch":
      path+="/branches";method="POST";body={name:String(args.name||"").slice(0,160),term:String(args.term||"").slice(0,512)};break;
    case "viewRemoveBranch":
      if(!positive(args.id))return {ok:false,error:"invalid_branch"};
      path+="/branches/"+args.id;method="DELETE";break;
    case "viewCompile":
      path+="/compile";method="POST";body={language:String(args.language||"en").slice(0,3)};timeout=60000;break;
    default:return {ok:false,error:"invalid_operation"};
  }
  const settings=await chrome.storage.local.get(["token"]);
  if(typeof settings.token!=="string"||!/^[0-9a-f]{64}$/i.test(settings.token))return {ok:false,error:"configure_token"};
  try{
    const response=await fetch("http://127.0.0.1:8224/api/tree/v1"+path,{
      method,headers:{"Content-Type":"application/json","X-SixStars-Token":settings.token},
      credentials:"omit",redirect:"error",cache:"no-store",body:body===undefined?undefined:JSON.stringify(body),
      signal:AbortSignal.timeout(timeout)
    });
    const data=await response.json().catch(()=>({}));
    return {...data,ok:response.ok&&data.ok!==false,error:response.ok?data.error:(data.error||"HTTP "+response.status)};
  }catch{return {ok:false,error:"tree_service_unavailable"};}
}
async function command(operation,args={}){
  let result=await chrome.runtime.sendMessage({type:"rootsTreeCommand",operation,...args});
  if(result?.error==="invalid_operation"&&operation.startsWith("view"))
    result=await localViewCommand(operation,args);
  if(!result?.ok){
    const error=result?.error||"Desktop Tree unavailable";
    if(error==="invalid_operation")throw new Error("Extension worker is out of date. Reload Roots at chrome://extensions and reopen Tree.");
    if(error==="HTTP 404"&&operation.startsWith("view"))throw new Error("Restart the updated Roots desktop host (launch.cmd). The source-view API is missing.");
    throw new Error(error);
  }
  return result;
}
function node(tag,value,cls){
  const n=document.createElement(tag);
  if(value!==undefined)n.textContent=value;
  if(cls)n.className=cls;
  return n;
}
function action(label,fn){
  const b=node("button",label);b.type="button";
  b.onclick=async()=>{b.disabled=true;try{await fn();}catch(e){status(e.message);}finally{b.disabled=false;}};
  return b;
}
function exactLink(raw){
  try{
    const u=new URL(raw);
    return u.protocol==="https:"&&!u.username&&!u.password&&!u.port&&
      /^(?:www\.)?(?:x\.com|twitter\.com)$/.test(u.hostname)&&
      /^\/(?:[A-Za-z0-9_]{1,25}\/status|i\/(?:web\/)?status)\/[1-9][0-9]{0,24}\/?$/.test(u.pathname)
      ?"https://x.com"+u.pathname.replace(/\/$/,""):"";
  }catch{return "";}
}
function linkFor(members){
  const confirmed=members.filter(f=>f.source.endsWith(".link-confirmed")).at(-1);
  if(confirmed&&exactLink(confirmed.body))return exactLink(confirmed.body);
  const urls=members.filter(f=>f.source.endsWith(".link")).map(f=>exactLink(f.body)).filter(Boolean);
  const ids=new Set(urls.map(url=>url.split("/").at(-1)));
  return ids.size===1?urls.find(url=>!url.includes("/i/web/status/"))||urls[0]:"";
}
function field(members,role){
  return members.filter(f=>f.source.endsWith("."+role)).at(-1)?.body||"";
}
function weightDetails(sequence,source){
  const details=node("details");details.append(node("summary","Lexical weights · 128 components"));
  const body=node("div");details.append(body);let requested=false;
  details.addEventListener("toggle",async()=>{
    if(!details.open||requested)return;requested=true;body.textContent="Loading native Tree weights…";
    try{
      const r=await command("viewWeights",{source,sequence});
      if(!details.isConnected)return;
      if(!Array.isArray(r.weights)||r.weights.length!==128)throw new Error("Weights unavailable");
      body.replaceChildren(node("p","Normalized byte-trigram features used by Roots GGUF compilation; not learned relation strength.","muted"));
      const grid=node("div",undefined,"weight-grid");
      r.weights.forEach((w,i)=>{
        const cell=node("span",Number(w).toFixed(3));cell.title="Weight "+i;
        if(Number.isFinite(w))cell.style.backgroundColor="rgba(81,171,213,"+(0.12+Math.min(1,Math.max(0,w))*0.7)+")";
        grid.append(cell);
      });body.append(grid);
    }catch(e){requested=false;body.textContent=e.message;}
  });
  return details;
}
function renderRecord(item,source){
  const members=[...item.members].sort((a,b)=>a.sequence-b.sequence);
  const a=node("article"),body=members.find(f=>f.source.endsWith(".body"))||members[0];
  const lang=el("language").value;
  const localized=members.filter(f=>f.source.endsWith("."+lang+".translation")||f.source.endsWith("."+lang+".localized")).at(-1);
  a.append(node("strong",source==="experience"?(field(members,"process")||"Desktop"):[field(members,"author"),field(members,"handle")].filter(Boolean).join(" ")||field(members,"topic")||"Knowledge"));
  if(source==="experience")a.append(node("span",field(members,"origin").replace("windows.","")||field(members,"kind")||"observation","source-badge"));
  if(body)a.append(node("div",new Date(item.observedMs??body.observedMs).toLocaleString(),"muted"));
  a.append(node("p",localized?.body||body?.body||""));
  const url=linkFor(members);
  if(url){
    const link=node("a","Open original post");
    link.href=url;link.target="_blank";link.rel="noopener noreferrer";a.append(link);
  }
  const details=node("details");details.append(node("summary","Record fields · "+members.length));
  for(const f of members){
    details.append(node("small",f.source+(f.language?" · "+f.language:"")),node("p",f.body));
  }
  a.append(details);if(body)a.append(weightDetails(body.sequence,source));return a;
}
function renderScreen(item){
  const a=node("article",undefined,"screen-card");
  const observations=[...(item.observations||[])].sort((a,b)=>a.sequence-b.sequence);
  const rows=observations.map(observation=>{
    const members=observation.members||[];
    const body=members.find(f=>f.source==="record.experience.body");
    return {observation,members,body,origin:field(members,"origin").replace("windows.","")||"text"};
  }).filter(row=>row.body);
  const header=node("div",undefined,"row");
  header.append(node("strong",item.process||"Desktop"),node("span","Screen observations","source-badge"));
  a.append(header);
  const counts=new Map();
  for(const row of rows)counts.set(row.origin,(counts.get(row.origin)||0)+1);
  const methods=[...counts].map(([method,count])=>count+" "+method).join(" · ");
  a.append(node("div",new Date(item.observedMs).toLocaleTimeString()+" · "+rows.length+" observations"+(methods?" · "+methods:""),"muted"));
  const preview=node("div",undefined,"screen-preview");
  for(const row of rows.slice(0,5))preview.append(node("p",row.body.body));
  if(rows.length>5)preview.append(node("small","+"+(rows.length-5)+" more"));
  a.append(preview);
  const details=node("details");details.append(node("summary","Open screen · "+rows.length+" observations"));
  const contents=node("div");details.append(contents);
  let populated=false;
  details.addEventListener("toggle",()=>{
    if(!details.open||populated)return;populated=true;
    for(const row of rows){
      const observation=node("div",undefined,"screen-observation");
      observation.append(node("small",row.origin),node("p",row.body.body),weightDetails(row.body.sequence,"experience"));
      const metadata=node("details");metadata.append(node("summary","Source and coordinates"));
      for(const f of row.members)if(f!==row.body)metadata.append(node("small",f.source),node("p",f.body));
      observation.append(metadata);contents.append(observation);
    }
  });
  a.append(details);
  return a;
}
function appendTimelineItem(item,source,view){
  const observed=Number(item.observedMs);
  if(Number.isFinite(observed)){
    const date=new Date(observed);
    const day=date.toLocaleDateString(undefined,{weekday:"long",year:"numeric",month:"long",day:"numeric"});
    if(day!==timelineDay){timelineDay=day;el("items").append(node("h3",day,"timeline-day"));}
  }
  el("items").append(view==="screens"?renderScreen(item):view==="records"?renderRecord(item,source):renderFragment(item,source));
}
function renderFragment(f,source){
  const a=node("article");a.append(node("small",f.source+(f.language?" · "+f.language:"")),
    node("div",new Date(f.observedMs).toLocaleString(),"muted"),node("p",f.body),weightDetails(f.sequence,source));return a;
}
async function load(reset=false,live=false){
  if(reset){
    generation++;
    if(!live){query=el("term").value;mode=el("view").value;selectedAgent=Number(el("branchFilter").value)||0;}
    beforeMs=0;beforeSequence=0;pages=0;timelineDay="";loading=false;
  }
  if(loading)return;
  const gen=generation,previous=beforeSequence,source=selectedSource,view=mode;
  loading=true;el("more").disabled=true;
  try{
    const data=await command(view==="screens"?"viewScreens":view==="records"?"viewRecords":"viewFeed",{source,term:query,beforeMs,beforeSequence,agent:selectedAgent});
    if(gen!==generation)return;
    const rows=[...(data.items||[])].sort((a,b)=>Number(b.observedMs)-Number(a.observedMs)||Number(b.sequence)-Number(a.sequence));
    if(reset){el("items").replaceChildren();timelineDay="";}
    for(const item of rows)appendTimelineItem(item,source,view);
    if(reset&&!rows.length)el("items").append(node("p",view==="screens"?"No screen text observations yet. Grouped records also shows activity intervals.":"No matching knowledge yet.","muted"));
    beforeMs=data.nextBeforeMs||0;beforeSequence=data.nextBeforeSequence||0;pages++;
    el("more").hidden=!(data.hasMore??(rows.length===(view==="screens"?screenPageSize:30)))||!beforeSequence||beforeSequence===previous;
  }catch(e){if(gen===generation){if(reset)el("items").replaceChildren(node("p",e.message,"muted"));status(e.message);}}
  finally{if(gen===generation){loading=false;el("more").disabled=false;}}
}
async function refreshSelected(force=false){
  if(viewRefreshing&&!force)return;
  const epoch=++viewEpoch,source=selectedSource;viewRefreshing=true;
  try{
    const state=await command("viewStatus",{source});
    if(epoch!==viewEpoch||source!==selectedSource)return;
    const changed=viewRevision!==null&&viewRevision!==state.revision;
    el("knowledgeSummary").textContent=state.count+" fragments · revision "+state.revision;
    el("sourceDescription").textContent=source==="experience"?"Private desktop observations · screen groups collect text from one reading pass. Never included in Axon or Mesh exchange.":"Social and received knowledge. Newest observation time first.";
    const branches=await command("viewBranches",{source});
    if(epoch!==viewEpoch||source!==selectedSource)return;
    const signature=source+JSON.stringify(branches.items||[]);
    if(signature!==branchSignature){
      branchSignature=signature;el("branches").replaceChildren();
      const previous=Number(el("branchFilter").value)||0;
      el("branchFilter").replaceChildren(new Option("All branches","0"));
      for(const branch of branches.items||[]){
        el("branchFilter").add(new Option(branch.name,String(branch.id)));
        const row=node("div",undefined,"row");row.append(node("span",branch.name+" · "+branch.term),action("Remove",async()=>{
          await command("viewRemoveBranch",{source,id:branch.id});
          if(source===selectedSource){await refreshSelected(true);await load(true);}
        }));el("branches").append(row);
      }
      if([...el("branchFilter").options].some(o=>Number(o.value)===previous))el("branchFilter").value=String(previous);
      else if(previous){selectedAgent=0;await load(true);}
    }
    const canRefresh=el("liveKnowledge").checked&&pages<=1&&!loading&&!el("items").querySelector("details[open]")&&!el("term").matches(":focus");
    if(changed&&canRefresh){await load(true,true);if(epoch===viewEpoch&&source===selectedSource)viewRevision=state.revision;}
    else if(changed){el("knowledgeSummary").textContent+=" · new data available";}
    else viewRevision=state.revision;
  }catch(e){if(epoch===viewEpoch)el("knowledgeSummary").textContent="View unavailable · "+e.message;}
  finally{if(epoch===viewEpoch)viewRefreshing=false;}
}
function renderPeers(peers){
  const signature=JSON.stringify(peers);
  if(peerSignature===signature)return;
  peerSignature=signature;el("peers").replaceChildren();
  for(const p of peers){
    const row=node("div",undefined,"row"),external=p.transport&&p.transport!=="Private LAN";
    row.append(node("span",(p.host||"Offline")+" · "+p.pin.slice(0,12)+" · "+(p.transport||"Private LAN")+
      (external?" · "+(p.received||0)+" received · "+(p.status||"Bound"):"")),
      action(external?(p.blocked?"Unblock":"Block"):"Unbind",async()=>{
        if(external)await command("public-block",{pin:p.pin,blocked:!p.blocked});
        else await command("unbind",{pin:p.pin});
        await refresh();
      }));el("peers").append(row);
  }
}
async function renderInvites(mesh){
  if(!mesh?.listening){invitations=[];inviteSignature="";el("invites").replaceChildren();return;}
  const result=await command("invitations"),items=result.items||[];
  const signature=JSON.stringify(items);
  if(signature===inviteSignature)return;
  invitations=items;inviteSignature=signature;el("invites").replaceChildren();
  for(const uri of invitations){
    let host;try{host=new URL(uri).searchParams.get("host");}catch{continue;}
    el("invites").append(action("Copy invitation · "+host,async()=>{
      await navigator.clipboard.writeText(uri);status("Invitation copied.");
    }));
  }
}
async function refresh(){
  if(refreshing)return;refreshing=true;
  try{
    latest=await command("status");
    el("summary").textContent=latest.count+" fragments · Tree revision "+latest.revision;
    el("capture").disabled=false;el("capture").checked=latest.capture===true;
    const mesh=latest.mesh;
    el("listen").disabled=!mesh?.lan;el("listen").textContent=mesh?.enabled?"Stop listening":"Start listening";
    el("receive").disabled=!mesh?.lan||(!mesh.publicDiscovery&&!mesh.peers?.length)||mesh.receiving;
    el("receive").textContent=mesh?.receiving?"Receiving…":"Receive missing knowledge";
    const result=mesh?.receiveResult;
    el("meshStatus").textContent=mesh
      ? (mesh.lan?mesh.status:"Local-only mode")+" · "+mesh.receiveStatus+" · "+(mesh.peers||[]).filter(p=>!p.blocked).length+" Axon subscriptions"+(mesh.publicStatus?" · "+mesh.publicStatus:"")+(result?" · "+result.inserted+" added · "+result.missing+" pending · "+result.failed+" errors":"")
      : "Mesh unavailable"+(latest.meshError?": "+latest.meshError:"");
    renderPeers(mesh?.peers||[]);
    await renderInvites(mesh);
  }catch(e){
    el("summary").textContent="Desktop Tree unavailable. Check that the updated Roots host is running and the extension key is saved.";
    el("capture").disabled=true;el("listen").disabled=true;el("receive").disabled=true;status(e.message);
  }finally{refreshing=false;}
}
function handle(id,event,fn){
  el(id).addEventListener(event,async e=>{
    e.preventDefault();
    try{await fn();}catch(error){status(error.message);}
  });
}
handle("capture","change",async()=>{const enabled=el("capture").checked;el("capture").disabled=true;try{await command("capture",{enabled});}finally{await refresh();}});
handle("refresh","click",async()=>{await refresh();viewRevision=null;await refreshSelected(true);await load(true);});
handle("listen","click",async()=>{await command("listen",{enabled:!latest?.mesh?.enabled});await refresh();});
handle("receive","click",async()=>{await command("receive");status("Receive started in the desktop host.");await refresh();});
handle("bind","submit",async()=>{await command("bind",{invitation:el("invitation").value.trim()});el("invitation").value="";status("Axon bound.");await refresh();});
handle("branch","submit",async()=>{await command("viewAddBranch",{source:selectedSource,name:el("branchName").value,term:el("branchTerm").value});el("branchName").value="";el("branchTerm").value="";await refreshSelected(true);});
handle("compile","click",async()=>{el("compile").disabled=true;const source=selectedSource;try{
  const r=await command("viewCompile",{source,language:el("language").value});
  status(source==="experience"?(r.available?"Private Experience GGUF compiled · revision "+r.revision:"No current Experience snapshot published; retry after capture settles."):(r.available?"Localized Tree snapshot compiled.":"No localized knowledge yet."));
}finally{el("compile").disabled=false;}});
handle("knowledgeSource","change",async()=>{
  selectedSource=el("knowledgeSource").value;viewRevision=null;branchSignature="";selectedAgent=0;
  el("compile").textContent=selectedSource==="experience"?"Compile private Experience GGUF":"Compile localized GGUF";
  const screenOption=el("view").querySelector('option[value="screens"]');
  screenOption.hidden=screenOption.disabled=selectedSource!=="experience";
  el("view").value=selectedSource==="experience"?"screens":"records";
  el("branchFilter").replaceChildren(new Option("All branches","0"));
  el("items").replaceChildren();generation++;loading=false;
  await refreshSelected(true);await load(true);
});
handle("branchFilter","change",()=>load(true));
handle("search","submit",()=>load(true));handle("view","change",()=>load(true));handle("language","change",()=>load(true));handle("more","click",()=>load());
chrome.storage.local.get(["targetLang"]).then(s=>{if([...el("language").options].some(o=>o.value===s.targetLang))el("language").value=s.targetLang;}).catch(()=>{}).finally(async()=>{await Promise.all([refresh(),refreshSelected(true)]);await load(true);});
setInterval(()=>{if(document.visibilityState==="visible"){refresh();refreshSelected();}},3000);
