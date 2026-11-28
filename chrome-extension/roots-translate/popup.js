const el = id => document.getElementById(id);
const status = message => { el("status").textContent = message; };
chrome.storage.local.get(["token","targetLang","autoTranslate"], settings => {
  el("token").value = settings.token || "";
  el("target").value = settings.targetLang || "en";
  el("auto").checked = settings.autoTranslate !== false;
});
el("save").addEventListener("click", async () => {
  const token = el("token").value.trim();
  if (!/^[a-f0-9]{64}$/i.test(token)) return status("Token must be 64 hexadecimal characters.");
  await chrome.storage.local.set({
    token, targetLang:el("target").value, autoTranslate:el("auto").checked
  });
  status("Saved. Reload X to apply translation settings.");
  refreshTree();
});
el("ping").addEventListener("click", () => {
  chrome.runtime.sendMessage({type:"sixStarsHealth"}, answer =>
    status(answer?.ok
      ? (answer.transport === "websocket"
          ? "Connected: persistent local WebSocket ready."
          : "Service online; using HTTP fallback (update/restart launcher for WebSocket).")
      : "Service unavailable. Start Roots and check port 8224."));
});

async function treeCommand(operation, values={}) {
  try{return await chrome.runtime.sendMessage({type:"rootsTreeCommand",operation,...values});}
  catch{return {ok:false,error:"Tree unavailable"};}
}
async function refreshTree(){
  const answer=await treeCommand("status");
  el("capture").disabled=!answer?.ok;
  el("capture").checked=answer?.ok&&answer.capture===true;
  el("treeStatus").textContent=answer?.ok
    ? answer.count+" fragments · "+(answer.mesh?.listening?"Mesh listening":"Mesh off")
    : "Tree unavailable. Start the updated Roots host.";
}
el("capture").addEventListener("change",async()=>{
  const enabled=el("capture").checked;
  el("capture").disabled=true;
  const answer=await treeCommand("capture",{enabled});
  if(!answer?.ok)status("Could not change capture. Check the host connection.");
  await refreshTree();
});
el("tree").addEventListener("click",()=>chrome.tabs.create({url:chrome.runtime.getURL("tree.html")}));
refreshTree();
