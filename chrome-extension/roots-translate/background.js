// The X page/content script never receives the key. Only this extension
// service worker talks to the desktop translation gateway.
chrome.storage.local.setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" }).catch(console.warn);

const HTTP_BASE = "http://127.0.0.1:8123/api/translation/v1";
const SOCKET_URL = "ws://127.0.0.1:8123/api/translation/v1/socket";
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

chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
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
