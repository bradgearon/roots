// The X page/content script never receives the key. Only this extension
// service worker talks to the desktop translation gateway.
chrome.storage.local.setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" }).catch(console.warn);

const HTTP_BASE = "http://127.0.0.1:8224/api/translation/v1";
const SOCKET_URL = "ws://127.0.0.1:8224/api/translation/v1/socket";
const ALLOWED_HOSTS = new Set(["x.com", "twitter.com"]);
const REQUEST_TIMEOUT_MS = 95000;
const HANDSHAKE_TIMEOUT_MS = 2500;
const PING_INTERVAL_MS = 20000; // Chrome 116+: keeps the MV3 worker active.

const activeRequests = new Map();       // tab/frame/request -> AbortController
const pendingSocketCalls = new Map();   // multiplexed socket id -> callbacks
let socket = null;
let socketToken = null;
let socketAuthenticated = false;
let socketConnection = null;
let heartbeat = null;
let socketSequence = 0;

function requestKey(sender, requestId) {
  return String(sender.tab?.id ?? -1) + ":" + String(sender.frameId ?? 0) + ":" + requestId;
}
function isAllowedXSender(sender) {
  try { return ALLOWED_HOSTS.has(new URL(sender.url).hostname); }
  catch { return false; }
}
function validToken(value) {
  return typeof value === "string" && /^[0-9a-f]{64}$/i.test(value);
}

function stopHeartbeat() {
  if (heartbeat !== null) clearInterval(heartbeat);
  heartbeat = null;
}
function onSocketLost(ws, reason) {
  if (socket === ws) {
    socket = null;
    socketToken = null;
    socketAuthenticated = false;
    socketConnection = null;
    stopHeartbeat();
  }
  for (const [id, operation] of pendingSocketCalls) {
    if (operation.ws !== ws) continue;
    pendingSocketCalls.delete(id);
    operation.finish(new Error(reason || "socket_closed"), null);
  }
}
function closeSocket(reason = "connection_changed") {
  const previous = socket;
  if (!previous) return;
  onSocketLost(previous, reason);
  try { previous.close(); } catch { /* Already disconnected. */ }
}
function startHeartbeat(ws) {
  stopHeartbeat();
  heartbeat = setInterval(() => {
    if (socket !== ws || ws.readyState !== WebSocket.OPEN) return;
    try { ws.send(JSON.stringify({type:"ping"})); }
    catch { closeSocket("heartbeat_failed"); }
  }, PING_INTERVAL_MS);
}

// Only the authenticated background worker owns this connection.
// Chrome may suspend/restart the worker; reconnect automatically on the next
// translation, or use the existing HTTP endpoint if no socket is available.
async function getSocket(token) {
  if (typeof WebSocket === "undefined") throw new Error("websocket_unavailable");
  if (!validToken(token)) throw new Error("invalid_token");

  if (socket && socketToken === token) {
    if (socketAuthenticated && socket.readyState === WebSocket.OPEN) return socket;
    if (socketConnection) return socketConnection;
  }
  closeSocket();

  const ws = new WebSocket(SOCKET_URL);
  socket = ws;
  socketToken = token;
  socketAuthenticated = false;

  const connection = new Promise((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      fail(new Error("websocket_handshake_timeout"));
    }, HANDSHAKE_TIMEOUT_MS);

    function fail(error) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      onSocketLost(ws, error.message);
      try { ws.close(); } catch { }
      reject(error);
    }
    function success() {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      socketAuthenticated = true;
      startHeartbeat(ws);
      resolve(ws);
    }

    ws.onopen = () => {
      try { ws.send(JSON.stringify({type:"auth", token})); }
      catch { fail(new Error("websocket_auth_send_failed")); }
    };
    ws.onmessage = event => {
      let message;
      try { message = JSON.parse(event.data); }
      catch { return; }

      if (message?.type === "auth") {
        if (message.ok === true) success();
        else fail(new Error("websocket_auth_rejected"));
        return;
      }
      if (!settled || !socketAuthenticated) return;
      if (message?.type !== "result" || typeof message.id !== "string") return;

      const pending = pendingSocketCalls.get(message.id);
      if (!pending || pending.ws !== ws) return;
      pendingSocketCalls.delete(message.id);
      pending.finish(null, message);
    };
    ws.onerror = () => {
      if (!settled) fail(new Error("websocket_connect_failed"));
    };
    ws.onclose = () => {
      if (!settled) fail(new Error("websocket_closed_before_auth"));
      else onSocketLost(ws, "websocket_disconnected");
    };
  });

  socketConnection = connection;
  return connection;
}

function translateOverSocket(ws, body, signal, operation = "translate") {
  return new Promise((resolve, reject) => {
    const id = "tr-" + (++socketSequence) + "-" +
      Math.random().toString(36).slice(2, 10);
    let finished = false;
    let timer = null;

    function finish(error, message) {
      if (finished) return;
      finished = true;
      if (timer !== null) clearTimeout(timer);
      signal.removeEventListener("abort", cancel);
      pendingSocketCalls.delete(id);
      if (error) reject(error);
      else resolve(message);
    }
    function cancel() {
      if (ws.readyState === WebSocket.OPEN) {
        try { ws.send(JSON.stringify({type:"cancel",id})); } catch { }
      }
      finish(new DOMException("cancelled", "AbortError"), null);
    }

    if (signal.aborted) { reject(new DOMException("cancelled", "AbortError")); return; }
    if (ws.readyState !== WebSocket.OPEN) {
      reject(new Error("websocket_disconnected"));
      return;
    }

    pendingSocketCalls.set(id, {ws, finish});
    signal.addEventListener("abort", cancel, {once:true});
    timer = setTimeout(() => {
      if (ws.readyState === WebSocket.OPEN) {
        try { ws.send(JSON.stringify({type:"cancel",id})); } catch { }
      }
      finish(new Error("translation_timeout"), null);
    }, operation === "detect" ? 5000 : REQUEST_TIMEOUT_MS);

    try { ws.send(JSON.stringify({type:operation,id,...body})); }
    catch { finish(new Error("websocket_send_failed"), null); }
  });
}

async function translateOverHttp(body, token, signal, operation = "translate") {
  const response = await fetch(HTTP_BASE + "/" + operation, {
    method:"POST",
    headers:{"Content-Type":"application/json","X-SixStars-Token":token},
    body:JSON.stringify(body),
    signal:AbortSignal.any([signal, AbortSignal.timeout(operation === "detect" ? 5000 : REQUEST_TIMEOUT_MS)])
  });
  if (!response.ok) throw new Error("Local translation returned HTTP " + response.status);
  return {ok:true,...await response.json(), transport:"http-fallback"};
}

async function translate(body, token, signal, operation = "translate") {
  // Fall back to HTTP only if a socket could not be established. Once a
  // request is sent over the socket, do not duplicate GPU work on disconnect.
  let ws;
  try { ws = await getSocket(token); }
  catch { return translateOverHttp(body, token, signal, operation); }
  const result = await translateOverSocket(ws, body, signal, operation);
  return result.ok === true
    ? {ok:true, ...result, transport:"websocket"}
    : {ok:false,error:result.error || "translation_unavailable"};
}

// Secret rotation invalidates the old connection. Content scripts receive
// only booleans, never the token or arbitrary settings.
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && changes.token) closeSocket("translation_token_changed");
});

// The browser worker owns the existing local token. Capturing DOM data
// must never grant a page access to Tree queries, peer credentials or controls.
const TREE_BASE = "http://127.0.0.1:8224/api/tree/v1";
async function treeCall(path, method="GET", body, timeout=10000) {
  const settings=await chrome.storage.local.get(["token"]);
  if(!validToken(settings.token))return {ok:false,error:"configure_token"};
  try{
    const response=await fetch(TREE_BASE+path,{
      method,headers:{"Content-Type":"application/json","X-SixStars-Token":settings.token},
      body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(timeout)
    });
    const result=await response.json().catch(()=>({}));
    return {...result,ok:response.ok&&result.ok!==false,error:response.ok?result.error:(result.error||"HTTP "+response.status)};
  }catch{return {ok:false,error:"tree_service_unavailable"};}
}
function allowedTreePage(sender) {
  return sender.id===chrome.runtime.id&&
    [chrome.runtime.getURL("popup.html"),chrome.runtime.getURL("tree.html")].includes(sender.url);
}
async function foregroundTreeSender(sender) {
  if(sender.id!==chrome.runtime.id||!isAllowedXSender(sender)||sender.frameId!==0||
    sender.documentLifecycle&&sender.documentLifecycle!=="active"||!Number.isInteger(sender.tab?.id))return false;
  try {
    const url=new URL(sender.url);
    if(url.protocol!=="https:"||/^\/(?:messages|compose|settings|i\/flow|i\/chat)(?:\/|$)/i.test(url.pathname))return false;
    const tab=await chrome.tabs.get(sender.tab.id);
    const win=await chrome.windows.get(tab.windowId);
    return tab.active&&win.focused;
  }catch{return false;}
}
function cleanTreePost(raw) {
  if(!raw||typeof raw.text!=="string"||!raw.text.trim()||raw.text.length>12000)return null;
  const out={text:raw.text};
  for(const [key,max] of Object.entries({author:240,handle:16,url:512,language:3,time:80,
    replyCount:48,repostCount:48,likeCount:48,viewCount:48,translation:12000,target:3})){
    if(typeof raw[key]==="string")out[key]=raw[key].slice(0,max);
  }
  for(const [key,max,count] of [["media",1000,8],["context",2000,8],["poll",300,8]])
    if(Array.isArray(raw[key]))out[key]=raw[key].filter(v=>typeof v==="string").slice(0,count).map(v=>v.slice(0,max));
  out.localized=raw.localized===true;
  // Bound the full UTF-8 envelope, not just JavaScript string lengths.
  return new TextEncoder().encode(JSON.stringify(out)).length<=90*1024?out:null;
}
async function treeCommand(request){
  switch(request.operation){
    case "viewStatus":case "viewRecords":case "viewFeed":case "viewWeights":
    case "viewBranches":case "viewAddBranch":case "viewRemoveBranch":case "viewCompile":{
      const source=request.source;
      if(source!=="experience"&&source!=="knowledge")return {ok:false,error:"invalid_source"};
      const path="/view/"+source;
      const positive=v=>Number.isSafeInteger(v)&&v>0?v:0;
      switch(request.operation){
        case "viewStatus":return treeCall(path+"/status");
        case "viewRecords":case "viewFeed":{
          const params=new URLSearchParams({term:String(request.term||"").slice(0,512),
            beforeMs:String(positive(request.beforeMs)),beforeSequence:String(positive(request.beforeSequence)),
            agent:String(positive(request.agent)),limit:"30"});
          return treeCall(path+(request.operation==="viewRecords"?"/records":"/feed")+"?"+params);
        }
        case "viewWeights":return positive(request.sequence)?treeCall(path+"/weights/"+request.sequence):{ok:false,error:"invalid_sequence"};
        case "viewCompile":return treeCall(path+"/compile","POST",{language:String(request.language||"en").slice(0,3)},60000);
        case "viewBranches":return treeCall(path+"/branches");
        case "viewAddBranch":return treeCall(path+"/branches","POST",{name:String(request.name||"").slice(0,160),term:String(request.term||"").slice(0,512)});
        case "viewRemoveBranch":return positive(request.id)?treeCall(path+"/branches/"+request.id,"DELETE"):{ok:false,error:"invalid_branch"};
      }
      break;
    }
    case "status":return treeCall("/status");
    case "capture":return treeCall("/capture","POST",{enabled:request.enabled===true});
    case "listen":return treeCall("/mesh/listen","POST",{enabled:request.enabled===true});
    case "invitations":return treeCall("/mesh/invitations");
    case "bind":return treeCall("/mesh/bind","POST",{invitation:String(request.invitation||"").slice(0,1024)});
    case "unbind":return treeCall("/mesh/unbind","POST",{pin:String(request.pin||"")});
    case "public-block":return treeCall("/mesh/public-block","POST",{pin:String(request.pin||""),blocked:request.blocked===true});
    case "receive":return treeCall("/mesh/receive","POST",{},1800000);
    case "records":case "feed":{
      const params=new URLSearchParams({term:String(request.term||"").slice(0,512),
        before:String(Math.max(0,Number.isSafeInteger(request.before)?request.before:0)),limit:"30"});
      return treeCall("/"+request.operation+"?"+params);
    }
    case "branches":return treeCall("/branches");
    case "addBranch":return treeCall("/branches","POST",{name:String(request.name||"").slice(0,160),term:String(request.term||"").slice(0,512)});
    case "removeBranch":{
      if(!Number.isSafeInteger(request.id)||request.id<1)return {ok:false,error:"invalid_branch"};
      return treeCall("/branches/"+request.id,"DELETE");
    }
    case "compile":return treeCall("/compile","POST",{language:String(request.language||"en").slice(0,3)},60000);
    default:return {ok:false,error:"invalid_operation"};
  }
}
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if(request?.type==="rootsTreeSettings"||request?.type==="rootsTreeObserve"){
    (async()=>{
      if(!await foregroundTreeSender(sender)){sendResponse({ok:false,error:"not_foreground"});return;}
      if(request.type==="rootsTreeSettings"){
        const status=await treeCall("/status");
        sendResponse({ok:status.ok,capture:status.ok&&status.capture===true,error:status.error});
      }else{
        const post=cleanTreePost(request.post);
        sendResponse(post?await treeCall("/observe","POST",post):{ok:false,error:"invalid_post"});
      }
    })().catch(()=>sendResponse({ok:false,error:"tree_service_unavailable"}));
    return true;
  }
  if(request?.type==="rootsTreeCommand"){
    if(!allowedTreePage(sender)){sendResponse({ok:false,error:"untrusted_sender"});return false;}
    treeCommand(request).then(sendResponse,()=>sendResponse({ok:false,error:"tree_service_unavailable"}));
    return true;
  }

  if (request?.type === "sixStarsCancel") {
    if (!isAllowedXSender(sender) || typeof request.requestId !== "string") {
      sendResponse({ok:false,error:"untrusted_sender"}); return false;
    }
    const controller = activeRequests.get(requestKey(sender, request.requestId));
    if (controller) controller.abort();
    sendResponse({ok:true,cancelled:!!controller});
    return false;
  }

  if (request?.type === "sixStarsTranslate" || request?.type === "sixStarsDetect") {
    if (!isAllowedXSender(sender)) {
      sendResponse({ok:false,error:"untrusted_sender"}); return false;
    }
    (async () => {
      const text = request.text;
      if (typeof text !== "string" || !text.trim() || text.length > 12000) {
        sendResponse({ok:false,error:"invalid_text"}); return;
      }
      const settings = await chrome.storage.local.get(["token","targetLang"]);
      if (!validToken(settings.token)) {
        sendResponse({ok:false,error:"Configure a valid local token in the Roots extension popup."});
        return;
      }
      const target = typeof settings.targetLang === "string" ? settings.targetLang : "en";
      const body = {
        text, targetLang:target,
        sourceLang: /^[a-z]{2,3}$/.test(request.sourceLang || "") ? request.sourceLang : undefined,
        maxTokens:512
      };
      const requestId = typeof request.requestId === "string"
        && /^[a-z0-9-]{1,60}$/i.test(request.requestId)
        ? request.requestId : "single";
      const key = requestKey(sender, requestId);

      const controller = new AbortController();
      activeRequests.get(key)?.abort(); // Reissued request replaces prior one.
      activeRequests.set(key, controller);
      try {
        if (request.auto === true || request.type === "sixStarsDetect") {
          const detected = await translate({text}, settings.token, controller.signal, "detect");
          if (!detected.ok || request.type === "sixStarsDetect") { sendResponse(detected); return; }
          if (!detected.reliable || detected.language === target.toLowerCase().split(/[-_]/)[0]) {
            sendResponse({ok:true, skipped:true, detection:detected}); return;
          }
          body.sourceLang = /^[a-z]{2,3}$/.test(detected.language) ? detected.language : undefined;
        }
        const result = await translate(body, settings.token, controller.signal);
        sendResponse(result);
      } catch (error) {
        sendResponse({ok:false,error:error?.name === "AbortError"
          ? "cancelled" : "Local translation unavailable: " + (error?.message || "unknown error")});
      } finally {
        if (activeRequests.get(key) === controller) activeRequests.delete(key);
      }
    })();
    return true;
  }

  if (request?.type === "sixStarsSettings") {
    if (!isAllowedXSender(sender)) {
      sendResponse({ok:false,error:"untrusted_sender"}); return false;
    }
    chrome.storage.local.get(["autoTranslate","targetLang"]).then(settings => {
      sendResponse({
        ok:true,
        autoTranslate:settings.autoTranslate !== false,
        targetLang:settings.targetLang || "en"
      });
    }, error => sendResponse({ok:false,error:String(error)}));
    return true;
  }

  if (request?.type === "sixStarsHealth") {
    (async () => {
      try {
        const response = await fetch(HTTP_BASE + "/health", {signal:AbortSignal.timeout(3000)});
        const health = await response.json();
        let wsReady = socketAuthenticated && socket?.readyState === WebSocket.OPEN;
        if (response.ok && !wsReady) {
          // The popup's connection check also establishes the persistent
          // transport so users can verify that WebSocket is really in use.
          const settings = await chrome.storage.local.get(["token"]);
          if (validToken(settings.token)) {
            try { await getSocket(settings.token); wsReady = true; }
            catch { /* Older gateway: retain working HTTP fallback. */ }
          }
        }
        sendResponse({ok:response.ok,...health,
          transport:wsReady ? "websocket" : "http-fallback"});
      } catch (error) { sendResponse({ok:false,error:error.message}); }
    })();
    return true;
  }
  return false;
});
