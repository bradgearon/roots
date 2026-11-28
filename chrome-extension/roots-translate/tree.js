"use strict";
const el=id=>document.getElementById(id);
const status=value=>{el("message").textContent=value;};
let latest=null,before=0,loading=false,refreshing=false,generation=0,query="",mode="records";
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
function renderRecord(item){
  const members=[...item.members].sort((a,b)=>a.sequence-b.sequence);
  const a=node("article"),body=members.find(f=>f.source.endsWith(".body"))||members[0];
  const lang=el("language").value;
  const localized=members.filter(f=>f.source.endsWith("."+lang+".translation")||f.source.endsWith("."+lang+".localized")).at(-1);
  a.append(node("strong",[field(members,"author"),field(members,"handle")].filter(Boolean).join(" ")||field(members,"topic")||"Knowledge"));
  if(body)a.append(node("div",new Date(body.observedMs).toLocaleString(),"muted"));
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
  a.append(details);return a;
}
function renderFragment(f){
  const a=node("article");a.append(node("small",f.source+(f.language?" · "+f.language:"")),node("p",f.body));return a;
}
async function load(reset=false){
  if(reset){generation++;query=el("term").value;mode=el("view").value;before=0;el("items").replaceChildren();loading=false;}
  if(loading)return;
  const gen=generation,previous=before;
  loading=true;el("more").disabled=true;
  try{
    const data=await command(mode,{term:query,before});
    if(gen!==generation)return;
    const rows=data.items||[];
    for(const item of rows)el("items").append(mode==="records"?renderRecord(item):renderFragment(item));
    if(reset&&!rows.length)el("items").append(node("p","No matching knowledge yet.","muted"));
    before=data.nextBefore||0;
    el("more").hidden=rows.length<30||!before||before===previous;
  }catch(e){if(gen===generation)status(e.message);}
  finally{if(gen===generation){loading=false;el("more").disabled=false;}}
}
function renderPeers(peers){
  const signature=JSON.stringify(peers);
  if(peerSignature===signature)return;
  peerSignature=signature;el("peers").replaceChildren();
  for(const p of peers){
    const row=node("div",undefined,"row");row.append(node("span",p.host+" · "+p.pin.slice(0,12)),action("Unbind",async()=>{
      await command("unbind",{pin:p.pin});await refresh();
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
    el("receive").disabled=!mesh?.lan||!mesh.peers?.length||mesh.receiving;
    el("receive").textContent=mesh?.receiving?"Receiving…":"Receive missing knowledge";
    const result=mesh?.receiveResult;
    el("meshStatus").textContent=mesh
      ? (mesh.lan?mesh.status:"Local-only mode")+" · "+mesh.receiveStatus+(result?" · "+result.inserted+" added · "+result.missing+" missing · "+result.failed+" errors":"")
      : "Mesh unavailable"+(latest.meshError?": "+latest.meshError:"");
    renderPeers(mesh?.peers||[]);
    await renderInvites(mesh);
    const branches=await command("branches");
    const signature=JSON.stringify(branches.items||[]);
    if(signature!==branchSignature){
      branchSignature=signature;el("branches").replaceChildren();
      for(const branch of branches.items||[]){
        const row=node("div",undefined,"row");row.append(node("span",branch.name+" · "+branch.term),action("Remove",async()=>{
          await command("removeBranch",{id:branch.id});await refresh();
        }));el("branches").append(row);
      }
    }
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
handle("refresh","click",async()=>{await refresh();await load(true);});
handle("listen","click",async()=>{await command("listen",{enabled:!latest?.mesh?.enabled});await refresh();});
handle("receive","click",async()=>{await command("receive");status("Receive started in the desktop host.");await refresh();});
handle("bind","submit",async()=>{await command("bind",{invitation:el("invitation").value.trim()});el("invitation").value="";status("Axon bound.");await refresh();});
handle("branch","submit",async()=>{await command("addBranch",{name:el("branchName").value,term:el("branchTerm").value});el("branchName").value="";el("branchTerm").value="";await refresh();});
handle("compile","click",async()=>{el("compile").disabled=true;try{const r=await command("compile",{language:el("language").value});status(r.available?"Localized Tree snapshot compiled.":"No localized knowledge yet.");}finally{el("compile").disabled=false;}});
handle("search","submit",()=>load(true));handle("view","change",()=>load(true));handle("language","change",()=>load(true));handle("more","click",()=>load());
chrome.storage.local.get(["targetLang"]).then(s=>{if([...el("language").options].some(o=>o.value===s.targetLang))el("language").value=s.targetLang;}).catch(()=>{}).finally(async()=>{await refresh();await load(true);});
setInterval(()=>{if(document.visibilityState==="visible")refresh();},3000);
