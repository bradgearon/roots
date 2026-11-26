# Native Roots Tree Core

Copyright (c) 2026 We Develop Software LLC. All rights reserved. See the repository LICENSE.

This is the first bounded, local C++17 implementation, intended to be shared by Android, desktop, and independent hosts. The C ABI in include/roots_tree.h is the boundary, not a C++ STL ABI. It exposes observation batches, durable SQLite records, exact query and cursor feed, persistent literal-interest agents, composable predicate keys, observed relations and GGUF v3 compilation.

The Android scroll-capture adapter, native JNI binding, desktop managed binding, and native Garden UI are subsequent integration work. The earlier Android-only Java prototype on feature/android-scroll-tree is NOT the canonical Tree implementation and must not be merged as a second core. No new capture is enabled by this library alone.

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
This produces libroots_tree.so. Use a pinned, security-reviewed SQLite release for shipping. The build deliberately does not download or silently select a dependency.

## Initial semantics and limits

- One authorized local Tree database per handle, with a mutex around SQLite work, WAL and atomic transactions. Use worker threads for batches and compilation; no work on the accessibility or presentation thread. Query callbacks run outside the mutex. Close only when all work has completed.
- Each fragment retains source, optional known external ID, original text, language hint and observation time. Same source/ID/body deduplicates; unknown IDs are fragments, not reconstructed tweet identities. Observing is not endorsement. No claim inference.
- Ordered predicate components are canonical dot-separated ASCII keys: is.a, is.from, is.in, is.part.of. Evidence-backed derived edges for fragment type, source and branch membership are the first implemented relations. Arbitrary authored claims and relationship updates are future contracts.
- Exact query is a literal substring with SQLite ASCII case folding, with sequence-cursor pagination. A simple agent backfills a literal matching branch and grows it automatically on new observations; no external browsing or model inference. Native cross-language normalization/semantic retrieval and broader research agents are follow-up work.
- Limits for this experiment: 10,000 unique fragments, 256 per batch, 32 agents, 16 KiB UTF-8 body per fragment, 100 per query. A batch exceeding capacity rolls back; report limits rather than silently dropping. The limits are deliberately provisional.
- GGUF v3 is a derived snapshot of 128 F32 normalized UTF-8 byte-trigram hash features, using the roots.lexical.utf8-trigram.f32.v1 profile, plus row keys and observed edge metadata. It is a lexical feature tensor, not a learned embedding, a language model, or a complete portable Tree. SQLite is authoritative. The container must be parsed by a compatible reader; llama.cpp model loading is not applicable. A caller creates a unique private temporary path, compiles off the UI thread, checks returned revision against current state and atomically publishes only an eligible snapshot. Retire stale exports when data is removed. Do not expose raw local data or snapshots to others by default.
- The core receives only already authorized observations; the host is responsible for capture consent, foreground/source restrictions, private surface exclusions, scope, revocation, and safe deletion of DB and sidecar files/exports. The initial database has no cross-user authorization or encryption layer. Keep in app-private storage, with backup disabled. Logical clear is not a forensic erasure guarantee.

## Next platform work

Reuse the existing Android X accessibility reader as an observation source, independently of translation and without a dwell requirement. Capture only available, permitted timeline fragments on events and lookahead; exclude uncertain or private contexts. Use immutable copies and a bounded queue; drain batches to the native C ABI. Do not make the compiler wait on scroll or translation. Measure actual fast-fling recall on a controlled feed; inaccessible or coalesced rows cannot be recovered by adding threads.

Use JNI on Android and P/Invoke on desktop. Both call the same native core, not a Java/C# reimplementation. Next: native library integration and test builds on ARM64 Android and MSVC Windows; feed UI and search; independent GGUF reader and portable manifest; task-specific semantic/poetic cognition; simple agent external-source adapters. Existing translation and Forge contracts remain unchanged.

No biological equivalence, measured social usefulness or production scalability is asserted.
