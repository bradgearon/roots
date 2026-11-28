# Whirlwind experimental transport

Two separate networks: Internal Mesh private compute and External Mesh shared knowledge. The same Rust crate contains independently instantiated protocol and key domains. Internal Mesh is NOT a public Axon. The current implementation does not publish private membership in the external DHT; Iroh public address lookup is disabled; privately share the printed endpoint + relay route. Relays still observe endpoint IDs and connection metadata. Private automatic route refresh remains follow-up work. Neither external BLE nor the DHT gives an inference grant.

## Windows + Android acceptance

1. Install Rust MSVC stable, the Android NDK, ARM64 Rust target (rustup target add aarch64-linux-android) and cargo-ndk. Run build-whirlwind.cmd all. Build and install Android as usual (build-android.cmd :app:installDebug). The Rust library is packaged separately from the C++ brain and is optional for LAN-only installs.
2. On Android open Intelligence settings and tap Copy my Internal Mesh device identity. Transfer the two PUBLIC identity lines to your PC privately. Run allow-internal-device.cmd and paste each line at its prompt. Grant only devices you recognize. This is an explicit transport allowlist; friends may add their own host and grant your device on that host. Removing both identities denies subsequent sessions (existing bounded sessions can finish).
3. On Windows run launch-intelligence-remote.cmd. It launches the existing verified Qwen intelligence host with the localhost-only HTTPS gateway on 8221 and an owned Whirlwind child. The llama.cpp GPU backend is still localhost 8082. The remote transport prints roots+iroh://<hex endpoint ID>?relay=<https relay URL>. Keep this private; if the home relay changes before private route resolution is implemented, refresh the route from the host. No inbound port forwarding or public HTTP listener is needed for Iroh relay operation.
4. On the PC run get-intelligence-key.cmd. Securely transfer the existing separate token:certificate-pin pairing key to your phone. In Intelligence settings use the roots+iroh:// address in place of the PC LAN address, paste the key, Save, Test, then enable Use desktop intelligence. The client checks the same model identity, TLS certificate and HTTP capability token. Existing LAN HTTPS settings continue to work.
5. Switch the phone to cellular and test again. The selected GPU is still the PC host's configured pinned model. The desktop must remain on and running. No automatic local or cloud fallback. Poetic Movement and element-flow profiling remain on-device, as before.
6. Optional independent fallback: set ROOTS_INTERNAL_LIBP2P_RELAY to a trusted libp2p relay multiaddress ending in its peer ID before launching the host. It requests a Circuit Relay reservation. Copy a printed usable libp2p route ending in the desktop's Peer ID and append &fallback=<multiaddr> to the Iroh address. The client attempts fallback only during pre-request setup; no automatic re-submission of a generation. Direct libp2p multiaddresses work if reachable. No relay server or fallback address is bundled.

## External directory prototype

Run a publicly reachable external bootstrap/router using a separate key file:
```cmd
set ROOTS_EXTERNAL_LISTEN=/ip4/0.0.0.0/tcp/8240
native\whirlwind\target\release\roots-whirlwind.exe external-dht external-seed.key
```
Use a reachable address replacing 0.0.0.0 in its printed multiaddress. Permit only the chosen router port, not the private inference gateway. Then publish a demo public directory card from a different terminal:
```cmd
native\whirlwind\target\release\roots-whirlwind.exe external-publish external-publisher.key "Music and Harmonics" music,harmony <external-seed-multiaddr>
```
Search from a third identity:
```cmd
native\whirlwind\target\release\roots-whirlwind.exe external-search external-client.key music <external-seed-multiaddr>
```
Use "*" instead of a topic to sample browse shards. Android: Roots Mesh > Manage nearby exchange > Network Explorer > External bootstrap settings. Enter a reachable seed address (not an internal compute route), then search a tag or leave blank to browse. Nearby saved bindings are shown separately.

Public node cards are signed with the external libp2p key, expire in 24 hours, and are republished by the running publisher. DHT provider records are untrusted discovery hints; the client verifies the actual signed card, publisher Peer ID, expiry and topic match. Directory cards currently advertise only directory capability, not a complete .axon, and no Internet data exchange is attempted from a search result. Nearby BLE auto-binding still works. Internet auto-join and publishing real Axon inventories require the public exchange stream and a durable verified member/Axon identity binding. Public bootstraps are not bundled. DHT stores are memory-only; publishers refresh. See design/network-explorer.md.

## Limitations and safety

The initial transport grants are explicit endpoint allowlists and the preexisting shared desktop inference token, not a full community invitation/grant format. Endpoint identities are stored in app-private no-backup or Windows per-user storage, not yet hardware-backed. Relay metadata is observable to the relay; the private endpoint is not published to public DNS/Pkarr lookup. The internal bridge only targets fixed localhost 8221; it does not route arbitrary ports. Maximum four simultaneous inbound streams and 100-second bridge timeout, setup is bounded; existing gateway validation and GPU queue remain in force. No automatic request replay. A malicious local app can race a one-shot loopback port but cannot impersonate the pinned desktop or obtain the bearer by TLS interception. Production requires native builds, independent security review, access-key lifecycle, individual device capability tokens, private route resolution, friend compute selection and network benchmarks. Cargo.lock has not yet been generated; run cargo generate-lockfile and review/pin transitive dependencies before production merge.
