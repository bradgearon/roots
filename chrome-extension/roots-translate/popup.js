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
  status("Saved. Reload X to apply.");
});
el("ping").addEventListener("click", () => {
  chrome.runtime.sendMessage({type:"sixStarsHealth"}, answer =>
    status(answer?.ok
      ? (answer.transport === "websocket"
          ? "Connected: persistent local WebSocket ready."
          : "Service online; using HTTP fallback (update/restart launcher for WebSocket).")
      : "Service unavailable. Start Roots and check port 8123."));
});
