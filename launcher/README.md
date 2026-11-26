# Roots Launcher — official AOSP derivative

This is an independent Android launcher artifact, **not** a replacement for the existing Roots Android app, and not a fork that accumulates edits to Google’s source. The official Launcher3 repository is https://android.googlesource.com/platform/packages/apps/Launcher3/. The official platform release selector is https://android.googlesource.com/platform/manifest/+/refs/heads/android-latest-release/default.xml. AOSP Launcher3 remains under its upstream licenses; Roots additions retain the repository's LICENSE.

## Architecture and hard boundary

- **Upstream:** Android Repo resolves Launcher3 and its matching platform dependencies from Google's official manifest. Keep that workspace dedicated and unmodified; never commit vendor code into an AOSP project or patch its tracked files.
- **Stock baseline:** build Google's unmodified `Launcher3` target first. This is a regression reference, not an APK to install over an OEM launcher.
- **Roots module:** a separate Soong module under `vendor/wds/roots_launcher` references the stock source filegroups and `Launcher3ResLib`. The generator adapts only copies of the manifest and build configuration in that vendor directory, preserving stock code and resources. A tiny subclass, `com.wds.roots.launcher.RootsLauncher`, is the sole HOME activity entry point. Existing Roots remains `com.wds.roots`.
- **Integration:** put future collaborators in Roots-owned source files, use explicit lifecycle hooks and composition, and keep the launcher functional when Roots is absent or disabled. `RootsBridge` provides an explicit user-triggered app-launch helper; no gesture or UI has been wired to it yet. The existing Roots app can already appear in the stock app drawer.
- **Privileged variant:** the separate `quickstep` build command produces Google's upstream Quickstep target for matching AOSP system images. It is not the regular-phone installation route. Do not request root, platform signing, hidden-API exemptions, privileged permission grants, or system-app replacement for the normal Roots artifact.

## Build on Linux

AOSP uses Soong rather than the existing Roots Windows/Gradle build. Use a Linux x86-64 build machine (Windows users can experiment with WSL2 on its Linux filesystem; native Windows is not an official AOSP build host). Follow Google's prerequisites and install Git, the official Repo tool, Python 3, Android SDK command-line tools (`apkanalyzer`), and SDK build-tools (`apksigner`). Full AOSP source and build output can require hundreds of GB of disk and substantial memory. See https://source.android.com/docs/setup/start and https://source.android.com/docs/setup/download.

From the Roots checkout:

```sh
python3 launcher/launcher.py self-test
python3 launcher/launcher.py status
python3 launcher/launcher.py --aosp ~/aosp-roots-launcher sync
python3 launcher/launcher.py --aosp ~/aosp-roots-launcher baseline
python3 launcher/launcher.py --aosp ~/aosp-roots-launcher build
```

The default is Google's moving `android-latest-release` manifest. At the time this adapter was authored, it resolves to Android 17; do not assume a new platform build will run on an older phone. For the device's Android version, use a matching verified official manifest ref via `--branch refs/tags/<official-tag>` or `--branch <official-release-branch>` **and a different empty workspace**. For a different platform lunch target, use `--lunch <target>` before the subcommand. The default is the AOSP Cuttlefish phone target. Adjust `--jobs` as needed.

Set `APKANALYZER` to the Linux SDK command-line tool executable if it isn't on PATH. The build must pass manifest checks for the unique package, HOME activity, isolated content providers and permissions. Both stock and derived APKs, the complete resolved source manifest, artifact SHA-256s, adapter input hashes and provenance are placed in a unique directory under the AOSP workspace's `.roots-launcher/dist/<manifest-hash>/build-*/`. No automatic device installation or update occurs.

For your test phone, create a local signing keystore (keep it outside Git; use the same key for subsequent updates), then re-sign only the verified Roots APK. Never install the publicly test-signed AOSP build as a trusted app.

```sh
export APKSIGNER=/path/to/android-sdk/build-tools/<version>/apksigner
python3 launcher/launcher.py sign /path/to/RootsLauncher-aosp-test-signed.apk \
  --keystore /path/to/local-roots-launcher.jks --alias roots-launcher \
  --output /path/to/RootsLauncher.apk
adb install -r /path/to/RootsLauncher.apk
```

On the device choose **Settings > Apps > Default apps > Home app > Roots Launcher**. The existing launcher remains installed; switch back using the same settings page. The regular package is distinct from both stock and the Roots companion app. Do not use `adb root`, force-grant protected permissions, or replace system APKs.

## Upstream update protocol

1. Run `status` (no platform download required) to read the live official manifest and its resolved Launcher3 Git ref. In a synced workspace it also reports the locked local revisions. This is a check, not an update.
2. Preserve the last accepted build, signing key, and its source-lock XML. For a risk-free candidate use a separate workspace, or explicitly run `sync` in the existing dedicated workspace. Sync refuses dirty upstream projects and records a new revision-resolved manifest only after a successful sync. The full XML locks the platform, not just the launcher repository. It is retained as build provenance; one-command historical checkout restoration is not implemented yet.
3. Build stock and Roots against the same locked sources. Changes to upstream interfaces cause an explicit generator/build/verification failure; do not silently patch the stock code. Review and update only the Roots adapter or defer the upgrade.
4. Run the adapter self-tests and smoke-test the candidate on a disposable emulator/profile and a supported physical device before installing it as an update. Keep a last-known-good signed APK; avoid downgrading a newer workspace database without testing.
5. Promote the candidate only after the gates pass. No automatic device rollout, root access, vendor launcher removal or blind merge of upstream changes.

## Required device acceptance tests (not yet performed)

- Select Roots Launcher as HOME and boot/reboot; no crash loop. Switch back to the OEM Home app and verify rollback.
- App drawer, launch/return, folders, drag/drop, shortcuts, widgets and binding consent, wallpaper, icon layout, search, notification dots with opt-in, screen rotation, accessibility, work profile if present.
- Uninstall/disable Roots companion: launcher remains usable. Reinstall companion and use explicit integration only. No extra network, inference or data collection in the HOME activity.
- Test upgrade with the same signing key, preserve existing workspace, test on each supported Android version.
- Test system Back/Home/Recents with gesture and three-button modes. Third-party home apps cannot obtain the OEM Quickstep/Recents system integration or all Pixel Launcher proprietary features. Some Launcher3 privileged paths may require further compatibility work.

**Initial implementation status:** source/build orchestration, extension seam and manifest gates are provided. A full AOSP compilation and on-device smoke test are still required before claiming an installable, fully functional launcher. The ordinary Roots Gradle build is unchanged.
