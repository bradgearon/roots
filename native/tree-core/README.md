# Native Roots Tree Core

Copyright (c) 2026 We Develop Software LLC. All rights reserved. See the repository LICENSE.

This is the first bounded, local C++17 implementation, intended to be shared by Android, desktop, and independent hosts. The C ABI in include/roots_tree.h is the boundary, not a C++ STL ABI. It exposes observation batches, durable SQLite records, exact query and cursor feed, persistent literal-interest agents, composable predicate keys, observed relations and GGUF v3 compilation.

The experimental Android JNI adapter and local Tree viewer, desktop managed binding and local CLI now call this shared implementation. The earlier Android-only Java prototype on feature/android-scroll-tree is NOT the canonical Tree implementation and must not be merged as a second core. Capture is off by default and Android's native Tree build is optional. Platform builds/device behavior are not yet verified.

## Build

Linux or Windows with a compatible SQLite development package:
```sh
cmake -S native/tree-core -B build/tree-core -DCMAKE_BUILD_TYPE=Release
cmake --build build/tree-core --config Release
ctest --test-dir build/tree-core -C Release --output-on-failure
```

MSVC: configure with a SQLite package (e.g. vcpkg CMake toolchain and x64-windows-static-md triplet) or provide an approved SQLite amalgamation. The DLL is roots_tree.dll. Linux produces libroots_tree.so.

Android: use the Android NDK toolchain, ABI arm64-v8a, API 26 or later, and an approved SQLite amalgamation containing sqlite3.c and sqlite3.h. Android's private platform SQLite library is not a supported NDK dependency.
```sh
cmake -S native/tree-core -B build/tree-android \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DROOTS_SQLITE_AMALGAMATION_DIR=/path/to/approved/sqlite-amalgamation \
  -DROOTS_TREE_TESTS=OFF
cmake --build build/tree-android
```
This produces libroots_tree.so. Use a pinned, security-reviewed SQLite release for shipping. The default Android build provisions SQLite 3.54.1 from the official HTTPS source and verifies the pinned published SHA3-256 digest; an explicitly supplied amalgamation overrides it. Desktop builds still accept system SQLite or an approved amalgamation.

## Initial semantics and limits

- One authorized local Tree database per handle, with a mutex around SQLite work, WAL and atomic transactions. Use worker threads for batches and compilation; no work on the accessibility or presentation thread. Query callbacks run outside the mutex. Close only when all work has completed.
- Each fragment retains source, optional known external ID, original text, language hint and observation time. Same source/ID/body deduplicates; unknown IDs are fragments, not reconstructed tweet identities. Observing is not endorsement. No claim inference.
- Ordered predicate components are canonical dot-separated ASCII keys: is.a, is.from, is.in, is.part.of. Evidence-backed derived edges for fragment type, source and branch membership are the first implemented relations. Arbitrary authored claims and relationship updates are future contracts.
- Exact query is a literal substring with SQLite ASCII case folding, with sequence-cursor pagination. A simple agent backfills a literal matching branch and grows it automatically on new observations; no external browsing or model inference. Native cross-language normalization/semantic retrieval and broader research agents are follow-up work.
- Limits for this experiment: No fixed SQLite fragment count ceiling. Limits: 256 per batch, 32 agents, 16 KiB UTF-8 body per fragment, 100 per query. Android localized GGUF is a rolling active projection of up to 10,000 latest eligible nodes; older records remain in SQLite. Physical free storage still limits retention.
- GGUF v3 is a derived snapshot of 128 F32 normalized UTF-8 byte-trigram hash features, using the roots.lexical.utf8-trigram.f32.v1 profile, plus row keys and observed edge metadata. It is a lexical feature tensor, not a learned embedding, a language model, or a complete portable Tree. SQLite is authoritative. The container must be parsed by a compatible reader; llama.cpp model loading is not applicable. A caller creates a unique private temporary path, compiles off the UI thread, checks returned revision against current state and atomically publishes only an eligible snapshot. Retire stale exports when data is removed. Do not expose raw local data or snapshots to others by default.
- The core receives only already authorized observations; the host is responsible for capture consent, foreground/source restrictions, private surface exclusions, scope, revocation, and safe deletion of DB and sidecar files/exports. The initial database has no cross-user authorization or encryption layer. Keep in app-private storage, with backup disabled. Logical clear is not a forensic erasure guarantee.

## Platform integrations (experimental)

### Android

From the android directory, build normally to use the pinned automatic download, or explicitly supply an approved SQLite amalgamation (sqlite3.c and sqlite3.h):

```sh
./gradlew :app:assembleDebug -ProotsEnableTree=true -ProotsSqliteAmalgamationDir=/absolute/path/to/sqlite-amalgamation
```

On Windows use gradlew.bat. If the checkout lacks the wrapper, generate it with the documented Gradle version first. Default Android builds include the Tree native target and automatically provision a pinned SQLite amalgamation. Set -ProotsEnableTree=false only for an explicit translation-only build. With the Tree-enabled build installed, grant the existing X accessibility permission, open Tree of Life from the translation settings, and separately consent to local timeline capture. Search / refresh feed and branch agents use the native database. New local observations feed literal-interest branches. No automatic external research, remote upload, or semantic Lily engine is implied. The app retains text fragments, not verified tweet identities or author metadata. The Home route gate, private-surface exclusions and bounded capture diagnostics need testing against current X builds. Home may include protected posts; storage is local, not a claim of public redistribution rights.

The intake handler snapshots eligible accessibility nodes on a dedicated thread, a bounded queue feeds one batch writer, and a separate compiler creates revision-checked GGUF snapshots approximately four seconds after work is scheduled. It retries when the revision changes. This is not a hard timing guarantee; capacity, coalescing and missing accessibility content can lose observations. Pause drops queued intake; an in-flight transaction may finish. Clear serializes after writes and removes managed snapshots. No automatic capture while X is not foreground. The Tree UI uses cursor pagination and a worker for search. The lexical feed is not yet weighted semantic feed assembly.

### Desktop

Build the core with the commands above, then use the explicit absolute trusted library path. Windows PowerShell example:

```powershell
$env:ROOTS_TREE_LIBRARY = (Resolve-Path build/tree-core/Release/roots_tree.dll).Path
$db = Join-Path $env:LOCALAPPDATA 'Roots\Tree\tree.db'
dotnet run --project src/Wds.Roots.TreeCli -c Release -- --db $db observe manual sample en 'Music and mathematics'
dotnet run --project src/Wds.Roots.TreeCli -c Release -- --db $db agent-add Music music
dotnet run --project src/Wds.Roots.TreeCli -c Release -- --db $db feed music
dotnet run --project src/Wds.Roots.TreeCli -c Release -- --db $db compile (Join-Path $env:LOCALAPPDATA 'Roots\Tree\first.gguf')
```

Linux: export ROOTS_TREE_LIBRARY to the absolute libroots_tree.so path. CLI is intentionally separate from the translation HTTP/LAN gateway and does not start PLaMo; no automatic desktop observation or web synchronization. Choose a private database path and a new snapshot path each time; retire caller-managed exports on deletion. The C# binding uses SafeHandle, UTF-8 and synchronous native callbacks; hosts should dispatch work off the UI thread.

The CI workflow includes native checks, managed binding checks on Linux/MSVC, and a host JVM JNI check. The latter does not validate actual Android device capture or build. For standalone JNI contract tests configure with ROOTS_TREE_HOST_JNI=ON and a JDK. No claim of passing until runs are observed.

## Next platform work

Validate the existing experimental Android capture adapter with a controlled feed, including fast-fling recall, false-positive surfaces, process restart, consent changes and UI latency; inaccessible or coalesced rows cannot be recovered by adding threads. Test ARM64 integration with the approved SQLite dependency.

JNI and P/Invoke both call the same native core. Next: confirm Android and MSVC builds, then an independent GGUF reader and portable manifest, desktop capture/browser and Forge integration, weighted feed assembly, [Lily Tendril semantic sensors](../../design/tendrils.md) and external-source agents. Existing translation and Forge contracts remain unchanged.

No biological equivalence, measured social usefulness or production scalability is asserted.

Capture growth fix: duplicate observations use a conditional insert to avoid duplicate AUTOINCREMENT churn, plus a bounded cache of successful writes. Conservative English hints apply only to grouped bodies; a paged local migration adds English projections to eligible previously unlabeled bodies without altering originals. Unknown language remains unknown. Lifetime failed-write statistics remain visible after recovery. The active coordination window is not a storage limit.
