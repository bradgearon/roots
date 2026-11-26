# Desktop compute socket

The phone uses the existing translation WebSocket protocol: standard JSON requests with IDs and results. TLS protects the entire LAN connection, including the existing token in the upgrade header. No custom AES/HMAC message envelopes, timestamp checks, or replay cache.

The desktop starts a persistent local TLS identity when launched with `--lan`. Port 8223 is HTTPS/WSS; the browser extension uses HTTP/WS on loopback-only port 8224. The llama.cpp model remains on loopback port 8081. The self-signed certificate is pinned to its exact SHA-256 fingerprint, obtained from the desktop's `get-key.cmd` output together with the existing token. A certificate change requires a new paste. No trust-all or plaintext fallback. Keep the desktop user-local PFX file private.

Android network events update familiar-network routing state without polling. Actual requested inference opens or reuses the TLS socket. Explicit desktop-only tests send a fixed sample and never fall back. Normal inference can fall back locally. The host and client do not send idle heartbeat messages on the mobile socket; browser extension behavior is unchanged. Diagnostics report request stages and error categories without source or result content. Discovery remains out of scope. Integration testing required.
