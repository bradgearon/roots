# Roots zero-touch provisioning — implementation plan

Status: proposed design, not implemented. Based on the current Android and desktop code at 1675cc40e0432970278b12b959e2903306047071.

## Acceptance target

With Roots desktop already running and a fresh debug APK installed on an explicitly authorized ADB device, open the app to one first-run screen. Approve the disclosed data use and model terms, enable the Android accessibility service in system settings, return to X. No manual IP, shared secret, network remembrance, model download buttons, desktop-only test, or separate capture toggle. A regular APK installed independently on another phone has the same flow except for one secure initial desktop approval (or a standard out-of-band enrollment). Being on the same Wi-Fi alone is not authentication. No privileged permission bypass.

Existing installs keep their preferences, models and database. An explicit refusal or revocation is never overwritten by bootstrap. If the accessibility service is already enabled, do not open system settings again. Model provisioning must be independent of service permission, and neither a missing desktop nor an unfinished model download may block local capture after approval.

## Existing blockers

* OverlayPreferences has separate X processing, desktop processing, auto preview and endpoint settings.
* OverlayTranslationKeyStore stores a pasted token:certificate fingerprint. FastestComputeRouter separately requires an eligible remembered Wi-Fi network.
* LocalTranslationGateway uses a shared token for the loopback extension and mobile socket. Desktop TLS identity is already persistent.
* LocalPLaMoTranslationProvider and LocalIntelligenceProvider expose explicit download actions. PLaMo uses publisher hash validation; the default Qwen tier has a pinned artifact hash. Preserve verification.
* TreeRuntime requires capture scope version 2 and explicit capture_enabled; it starts only when X processing is permitted.
* RootsStreamActivity has no first-run coordinator. Build-Android.ps1 only executes Gradle; launch.cmd already starts the LAN listener by default.

## Proposed components

1. Desktop EnrollmentService: advertise _roots-compute._tcp via standard DNS-SD/mDNS, with stable host identity, protocol version and readiness/capabilities. Treat all advertisements as untrusted hints, never credentials. Use existing private LAN port 8223 TLS for enrollment and compute, 8224 loopback for installer bootstrap control. Keep llama server loopback only. Advertise only on explicitly enabled private interfaces; stop advertising when host stops. Separate bootstrap readiness from model readiness, so downloading/loading desktop PLaMo does not block discovery and pairing.
2. Android DesktopDiscovery: platform NsdManager, bounded foreground/setup discovery and network-change rediscovery. A stored host pin identifies a desktop even if its DHCP address changes. No periodic subnet scans. Ignore unapproved hosts, no VPN/cellular fallback. For target SDK 37+ handle platform LAN permission or approved service picker. If mDNS fails, use authenticated installer-supplied candidate address as fallback; never guess a desktop from its name.
3. Device enrollment: generate distinct credentials for each enrolled phone, never distribute the desktop browser token. Encrypted Android Keystore-backed credential storage, host stores token hashes and device grants/revocation. TLS pin is established by an independently trusted bootstrap channel or verified human confirmation; mDNS cannot establish trust. Use normal TLS and OS secure storage, not a new encryption scheme.
4. ProvisioningCoordinator: single durable state machine, runs on launch, return from system settings, network change, and model-download completion. Only recompute affected states; no continuous polling. States are independent: consent (unseen/approved/refused/revoked); service (disabled/active); enrollment (unpaired/pending/paired/revoked); translation/intelligence model (missing/queued/downloading/verifying/ready/retryable failure); capture (off/ready/active). In-progress flags never masquerade as completed work. Capture begins when its actual consent and system service requirements are satisfied. Desktop routing requires its own actual approval and verified peer. Transitions idempotent, persist only non-secret metadata, reconcile against real Android service status, download IDs and hashes, host identity and grant.
5. ModelProvisioner: default phone PLaMo 0.8B and Qwen3.5 0.8B; use pinned verified artifacts and resumable DownloadManager jobs on unmetered networks. Check disk space first. Fetch once after first-run model terms approval; show progress, resume after app restart, do not load both models into RAM merely to install. No re-download on an ordinary upgrade or repeated launch. If desktop advertises matching redistributable, signed/pinned artifact bytes, optional authenticated LAN cache transfer; do not expose desktop filesystem and do not confuse desktop 2B PLaMo with mobile 0.8B. Keep existing model verification and lazy intelligence lifecycle. FunctionGemma and alternate tiers remain optional, downloaded only on selection. Phone translation may use desktop immediately after pairing, without waiting for local weights.
6. Setup screen: one plain disclosure and positive action to enable the full requested workflow; clear scope of X visible text including possible private messages, private SQLite retention, selected text sent to the paired computer for inference, automatic downloads and applicable model terms. Optional scope exclusions in the same screen (local-only/no capture, not separate mandatory settings screens). No automatic consent merely because the APK was installed or ADB authorized. Default auto preview after approval, device language if supported, English fallback with visible setting. Open Android Accessibility settings only if service not active; check actual activation upon return/onServiceConnected. No automated clicks, secure settings edits, or permission bypass. Advanced controls remain for revocation and troubleshooting, not required setup.
7. Home status: compact status of capture, desktop and models with retry only for a failed component. Automatically run a lightweight authenticated capability check, no need for a test translation to enroll. If a desktop is faster on actual eligible work, route there; do not block capture or require benchmark before use. Keep existing measurement-driven route and local fallback; remember network only after authentication, with pin as identity. Stop sending if consent revoked or server identity changes.

## Two enrollment paths (not two user workflows)

### Developer install — default in build-android.cmd app:installDebug

The install command already runs on the same computer as the desktop host. This is a trusted local channel after the owner has authorized the ADB device. After successful Gradle install:
- Choose exactly the intended authorized serial (honor explicit ANDROID_SERIAL or a single eligible device; do not guess among multiple devices).
- Ask the running desktop over its authenticated loopback API for a short-lived, one-use enrollment grant, constrained to mobile compute; returned grant carries the pinned desktop certificate identity and candidate connection addresses. No token/key printed, clipboard, command-line argument, intent extra, shared storage or APK-embedded secret.
- Transfer the small bootstrap record via stdin to app-private storage using debug-only run-as, atomically publish it and launch Roots. Refuse on non-debuggable or unauthorized target. App verifies expiry, host pin and TLS; consumes/deletes grant after successful enrollment. If user hasn't approved desktop data use, do not exchange the grant or send X text until that approval.
- If host unavailable, finish install, launch setup and automatically fall back to normal discovery. Existing valid credentials skip bootstrap. Failure must not break APK installation or erase an existing pairing.
- No ADB grant of accessibility consent or automatic enabling of the service. ADB authorization and app data consent are different.

### Independently installed APK (including second phone)

Discover host without key/address entry. If host already paired, authenticate existing device grant silently. For an unknown host, establish trust once: standard out-of-band pin+one-use grant (QR/deep link), or desktop approval with a short authentication string bound to the exact TLS certificate and enrollment transcript and verified on both displays before granting a credential. Do not implement unauthenticated trust-on-first-use or release a grant based on a matching device name. No copying/pasting long keys. Desktop approval must be rate-limited and expire; ordinary LAN neighbors may discover the endpoint but never use inference without authorization. When several hosts are found, select one; when an approved host is found, prefer it. A later nearby/Axon transport can reuse the same identity and authorization model.

## Implementation order

A. First-run coordinator + consent disclosure + model auto-provisioning + automatic capture activation + home status. Test upgrade and revocation.
B. Per-device desktop grants + authenticated installer bootstrap. This is the priority for the existing development workflow: eliminate the manual key and IP without introducing a replacement pairing chore.
C. DNS-SD advertising and Android discovery + verified first-pair approval for independently installed devices. Handle changing addresses and multiple hosts.
D. Optional desktop model cache and later nearby transport; do not make these prerequisites for first-run readiness.

## Definition of done

Fresh debug install with desktop running: no key clipboard, IP typing, model download clicks, desktop/capture switches or test button. Verify database count increases after browsing X, desktop authenticated request appears in inference history, default models are independently queued and verified, local fallback works once PLaMo is available, default intelligence runs once Qwen is ready. Repeat after restart, app update, host IP change, host stopped, download interrupted, low disk, consent refused/revoked, multiple hosts, different certificate, expired/replayed grant, revoked device and multiple ADB targets. Fresh independent install on second phone requires only unified consent/system permission and one verified desktop approval. Confirm no private text or token in discovery, logs, shell arguments, or unapproved network traffic.

The current source does not implement these components. This document is a plan and acceptance contract, not a statement that provisioning already works.
