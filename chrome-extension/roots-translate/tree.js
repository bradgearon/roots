"use strict";
const el=id=>document.getElementById(id);
const status=value=>{el("message").textContent=value;};
let latest=null,beforeMs=0,beforeSequence=0,loading=false,refreshing=false,generation=0,query="",mode="records";
let selectedSource="knowledge",selectedAgent=0,pages=0,viewRevision=null,viewRefreshing=false,viewEpoch=0;
let invitations=[],inviteSignature="",peerSignature="",branchSignature="";
async function command(operation,args={}){
  const result=await chrome.runtime.sendMessage({type:"rootsTreeCommand",operation,...args});
  if(!result?.ok)throw new Error(result?.error||"Desktop Tree unavailable");
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
function renderFragment(f,source){
  const a=node("article");a.append(node("small",f.source+(f.language?" · "+f.language:"")),
    node("div",new Date(f.observedMs).toLocaleString(),"muted"),node("p",f.body),weightDetails(f.sequence,source));return a;
}
async function load(reset=false,live=false){
  if(reset){
    generation++;
    if(!live){query=el("term").value;mode=el("view").value;selectedAgent=Number(el("branchFilter").value)||0;}
    beforeMs=0;beforeSequence=0;pages=0;loading=false;
  }
  if(loading)return;
  const gen=generation,previous=beforeSequence,source=selectedSource;
  loading=true;el("more").disabled=true;
  try{
    const data=await command(mode==="records"?"viewRecords":"viewFeed",{source,term:query,beforeMs,beforeSequence,agent:selectedAgent});
    if(gen!==generation)return;
    const rows=data.items||[];
    if(reset)el("items").replaceChildren();
    for(const item of rows)el("items").append(mode==="records"?renderRecord(item,source):renderFragment(item,source));
    if(reset&&!rows.length)el("items").append(node("p","No matching knowledge yet.","muted"));
    beforeMs=data.nextBeforeMs||0;beforeSequence=data.nextBeforeSequence||0;pages++;
    el("more").hidden=rows.length<30||!beforeSequence||beforeSequence===previous;
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
    el("sourceDescription").textContent=source==="experience"?"Private desktop observations. Never included in Axon or Mesh exchange.":"Social and received knowledge. Newest observation time first.";
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
    if(changed&&canRefresh){await load(true,true);viewRevision=state.revision;}
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
handle("compile","click",async()=>{el("compile").disabled=true;try{const r=await command("compile",{language:el("language").value});status(r.available?"Localized Tree snapshot compiled.":"No localized knowledge yet.");}finally{el("compile").disabled=false;}});
handle("knowledgeSource","change",async()=>{
  selectedSource=el("knowledgeSource").value;viewRevision=null;branchSignature="";selectedAgent=0;
  el("branchFilter").replaceChildren(new Option("All branches","0"));
  el("items").replaceChildren();generation++;loading=false;
  await Promise.all([refreshSelected(true),load(true)]);
});
handle("branchFilter","change",()=>load(true));
handle("search","submit",()=>load(true));handle("view","change",()=>load(true));handle("language","change",()=>load(true));handle("more","click",()=>load());
chrome.storage.local.get(["targetLang"]).then(s=>{if([...el("language").options].some(o=>o.value===s.targetLang))el("language").value=s.targetLang;}).catch(()=>{}).finally(async()=>{await Promise.all([refresh(),refreshSelected(true),load(true)]);});
setInterval(()=>{if(document.visibilityState==="visible"){refresh();refreshSelected();}},3000);
