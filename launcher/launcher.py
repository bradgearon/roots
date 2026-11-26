#!/usr/bin/env python3
"""Build Launcher3 and a Roots derivative from an untouched, locked AOSP checkout."""
# Copyright (c) 2026 We Develop Software LLC. All rights reserved.
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
MANIFEST_URL = "https://android.googlesource.com/platform/manifest"
LAUNCHER_URL = "https://android.googlesource.com/platform/packages/apps/Launcher3"
LAUNCHER_PATH = "packages/apps/Launcher3"
VENDOR_PATH = "vendor/wds/roots_launcher"
DEFAULT_BRANCH = "android-latest-release"
DEFAULT_LUNCH = "aosp_cf_x86_64_only_phone-aosp_current-userdebug"
STOCK_ID = "com.android.launcher3"
APP_ID = "com.wds.roots.launcher"
HOME_ACTIVITY = APP_ID + ".RootsLauncher"
ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"
ET.register_namespace("android", ANDROID[1:-1])
ET.register_namespace("tools", TOOLS[1:-1])


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run(args, cwd=None, capture=False, env=None):
    return subprocess.run([str(a) for a in args], cwd=cwd, env=env, check=True,
                          text=True, stdout=subprocess.PIPE if capture else None).stdout


def git(path, *args):
    return run(["git", "-C", path, *args], capture=True).strip()


def source_manifest(aosp, locked):
    args = ["repo", "manifest"]
    if locked:
        args.append("-r")
    raw = run(args + ["-o", "-"], cwd=aosp, capture=True)
    require(ET.fromstring(raw).tag == "manifest", "Unexpected Repo manifest output")
    return raw


def launcher_project(xml):
    root = ET.fromstring(xml)
    projects = [p for p in root.findall("project")
                if p.get("path", p.get("name")) == LAUNCHER_PATH]
    require(len(projects) == 1 and projects[0].get("name") == "platform/packages/apps/Launcher3",
            "Official Launcher3 project not found exactly once in manifest")
    default = root.find("default")
    return projects[0].get("revision") or (default.get("revision") if default is not None else None)


def state_dir(aosp):
    return aosp / ".roots-launcher"


def config_path(aosp):
    return state_dir(aosp) / "config.json"


def read_config(aosp):
    require(config_path(aosp).is_file(), "Not a Roots-managed AOSP checkout. Run sync in a dedicated directory.")
    config = json.loads(config_path(aosp).read_text())
    require(config["manifest_url"] == MANIFEST_URL, "Unexpected manifest origin")
    return config


def clean_source(aosp):
    # Only a dedicated checkout may be used. Never reset, patch or clean upstream.
    require((aosp / LAUNCHER_PATH / "Android.bp").is_file(), "Launcher3 source missing; run sync")
    require(not git(aosp / LAUNCHER_PATH, "status", "--porcelain", "--untracked-files=all"),
            "Launcher3 has local changes. Preserve them elsewhere before continuing.")


def clean_projects(aosp):
    # Repo runs this for every checked-out project, not only Launcher3.
    run(["repo", "forall", "-e", "-c",
         'if test -n "$(git status --porcelain --untracked-files=normal)"; then '
         'echo "Dirty AOSP project: $REPO_PATH" >&2; exit 1; fi'], cwd=aosp)


def lock_digest(raw):
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def ensure_locked(aosp):
    read_config(aosp)
    clean_source(aosp)
    lock = state_dir(aosp) / "manifest.lock.xml"
    require(lock.is_file(), "No completed sync/source lock. Run sync.")
    raw = lock.read_text(encoding="utf-8")
    actual = source_manifest(aosp, True)
    require(raw == actual, "AOSP revisions changed since the recorded lock. Run sync explicitly.")
    revision = launcher_project(raw)
    require(revision == git(aosp / LAUNCHER_PATH, "rev-parse", "HEAD"),
            "Launcher3 HEAD differs from the locked revision")
    return raw


def remote_head(url, ref):
    if not ref:
        return "unknown revision"
    if re.fullmatch(r"[0-9a-f]{40}", ref):
        return ref + " (manifest pins a commit)"
    if not ref.startswith("refs/"):
        # Repo manifests normally use refs/heads or refs/tags.
        ref = "refs/heads/" + ref
    lines = run(["git", "ls-remote", url, ref, ref + "^{}"], capture=True).splitlines()
    require(lines, "Official upstream ref not found: " + ref)
    return lines[-1].split()[0]


def official_status(branch):
    # Read the small manifest repository, not the entire platform.
    with tempfile.TemporaryDirectory(prefix="roots-upstream-") as tmp:
        directory = Path(tmp)
        run(["git", "init", "-q", directory])
        run(["git", "fetch", "--quiet", "--depth=1", MANIFEST_URL, branch], cwd=directory)
        commit = git(directory, "rev-parse", "FETCH_HEAD")
        xml = git(directory, "show", "FETCH_HEAD:default.xml")
        root = ET.fromstring(xml)
        require(not any(root.findall(tag) for tag in ("include", "extend-project", "remove-project")),
                "Official manifest layout changed; use Repo to resolve it before checking updates")
        revision = launcher_project(xml)
        print("Official manifest (" + branch + "): " + commit)
        print("Official Launcher3 revision: " + str(revision))
        print("Official Launcher3 head: " + remote_head(LAUNCHER_URL, revision))


def status(args):
    aosp = args.aosp
    branch = args.branch or DEFAULT_BRANCH
    if config_path(aosp).exists():
        branch = read_config(aosp)["branch"]
        raw = ensure_locked(aosp)
        print("Locked Launcher3: " + launcher_project(raw), flush=True)
        print("Source-lock SHA256: " + lock_digest(raw), flush=True)
        print("Tracked Launcher3 revision: " + str(launcher_project(source_manifest(aosp, False))), flush=True)
        print("Official Launcher3 head: " + remote_head(
            LAUNCHER_URL, launcher_project(source_manifest(aosp, False))), flush=True)
        print("Local manifest HEAD: " + git(aosp / ".repo/manifests", "rev-parse", "HEAD"), flush=True)
    official_status(branch)


def sync(args):
    require(sys.platform.startswith("linux"), "AOSP builds require a Linux workspace")
    aosp = args.aosp
    require(aosp != HERE and HERE not in aosp.parents and aosp not in HERE.parents,
            "Keep the AOSP workspace separate from the Roots repository")
    aosp.mkdir(parents=True, exist_ok=True)
    state = state_dir(aosp)
    config = config_path(aosp)
    if config.exists():
        branch = read_config(aosp)["branch"]
        require(args.branch is None or args.branch == branch,
                "Use a separate AOSP directory for a different release branch")
        if (state / "manifest.lock.xml").exists():
            clean_projects(aosp)
        elif (aosp / LAUNCHER_PATH / "Android.bp").exists():
            clean_source(aosp)
    else:
        require(not any(aosp.iterdir()), "Choose a new empty dedicated AOSP directory")
        branch = args.branch or DEFAULT_BRANCH
        state.mkdir()
        config.write_text(json.dumps({"manifest_url": MANIFEST_URL, "branch": branch}, indent=2) + "\n")
    # Only explicit sync moves upstream. A failed sync leaves the previous lock unchanged.
    run(["repo", "init", "--partial-clone", "--no-use-superproject",
         "-u", MANIFEST_URL, "-b", branch], cwd=aosp)
    run(["repo", "sync", "-c", "-j" + str(args.jobs)], cwd=aosp)
    clean_source(aosp)
    clean_projects(aosp)
    raw = source_manifest(aosp, True)
    revision = launcher_project(raw)
    require(revision == git(aosp / LAUNCHER_PATH, "rev-parse", "HEAD"),
            "Manifest did not pin Launcher3 HEAD")
    digest = lock_digest(raw)
    archive = state / "locks"
    archive.mkdir(exist_ok=True)
    (archive / (digest + ".xml")).write_text(raw, encoding="utf-8")
    temporary = state / "manifest.lock.tmp"
    temporary.write_text(raw, encoding="utf-8")
    temporary.replace(state / "manifest.lock.xml")
    print("Locked official Launcher3 " + revision + "\nFull manifest lock: " + str(archive / (digest + ".xml")))


def make_manifest(raw):
    """Adapt only a generated manifest. Never write the original."""
    tree = ET.fromstring(raw)
    require(tree.get("package") == STOCK_ID, "Upstream manifest namespace changed; review adapter")
    require(not tree.get(ANDROID + "sharedUserId"), "Unexpected shared UID; review adapter")
    app = tree.find("application")
    require(app is not None, "Missing upstream application")
    homes = [activity for activity in app.findall("activity")
             if any(f.find("category[@" + ANDROID + "name='android.intent.category.HOME']") is not None
                    for f in activity.findall("intent-filter"))]
    require(len(homes) == 1, "Upstream HOME activity changed; review adapter")
    home = homes[0]
    require(home.get(ANDROID + "name") in (STOCK_ID + ".Launcher", ".Launcher"),
            "Unexpected HOME implementation; review adapter")
    # Changing the manifest package must not change relative Java component names.
    for elem in tree.iter():
        if elem.tag in ("activity", "activity-alias", "service", "receiver", "provider", "application"):
            for attribute in ("name", "targetActivity", "parentActivityName", "backupAgent", "appComponentFactory"):
                key = ANDROID + attribute
                value = elem.get(key)
                if value and value.startswith("."):
                    elem.set(key, STOCK_ID + value)
                elif value and "." not in value and not value.startswith(("@", "$")):
                    elem.set(key, STOCK_ID + "." + value)
        for key, value in list(elem.attrib.items()):
            value = value.replace("${applicationId}", APP_ID).replace("${packageName}", APP_ID)
            if key in (ANDROID + "authorities", ANDROID + "permission", ANDROID + "readPermission",
                       ANDROID + "writePermission") or (elem.tag in ("permission", "permission-group",
                       "uses-permission") and key == ANDROID + "name"):
                if value.startswith(STOCK_ID + "."):
                    value = APP_ID + value[len(STOCK_ID):]
            elem.set(key, value)
    tree.set("package", APP_ID)
    home.set(ANDROID + "name", HOME_ACTIVITY)
    home.set(ANDROID + "exported", "true")
    app.set(ANDROID + "label", "Roots Launcher")
    replace = [s.strip() for s in app.get(TOOLS + "replace", "").split(",") if s.strip()]
    if "android:label" not in replace:
        replace.append("android:label")
    app.set(TOOLS + "replace", ",".join(replace))
    queries = tree.find("queries")
    if queries is None:
        queries = ET.Element("queries")
        tree.insert(list(tree).index(app), queries)
    if not any(p.get(ANDROID + "name") == "com.wds.roots" for p in queries.findall("package")):
        ET.SubElement(queries, "package", {ANDROID + "name": "com.wds.roots"})
    # The upstream common manifest is merged from Launcher3ResLib, with the new
    # application's manifest package as applicationId. APK validation is mandatory.
    return ET.tostring(tree, encoding="unicode", xml_declaration=True) + "\n"


def make_build_config(raw):
    pattern = r'(\bAPPLICATION_ID\s*=\s*")com\.android\.launcher3(")'
    updated, count = re.subn(pattern, lambda m: m.group(1) + APP_ID + m.group(2), raw)
    require(count == 1, "BuildConfig.APPLICATION_ID changed; review adapter")
    return updated


def generate_module(upstream, output):
    bp = (upstream / "Android.bp").read_text()
    for module in ("launcher-non-platform-apis-defaults", "Launcher3ResLib",
                   "launcher-src", "launcher-src_no_quickstep", "launcher-build-config"):
        require(re.search(r'\bname\s*:\s*"' + re.escape(module) + r'"', bp),
                "Upstream build interface changed: " + module)
    minimum = re.search(r'\bmin_launcher3_sdk_version\s*=\s*"([0-9]+)"', bp)
    require(minimum, "Upstream minimum SDK definition changed; review adapter")
    manifest = make_manifest((upstream / "AndroidManifest.xml").read_text())
    config = upstream / "src_build_config"
    require(config.is_dir(), "Upstream build config directory changed")
    configs = sorted(p for p in config.rglob("*") if p.is_file() and p.suffix in (".java", ".kt"))
    require(configs, "Upstream build config is empty")
    adapted = []
    count = 0
    for path in configs:
        raw = path.read_text()
        if path.name == "BuildConfig.java":
            raw = make_build_config(raw)
            count += 1
        adapted.append((path.relative_to(config), raw))
    require(count == 1, "Expected one upstream BuildConfig.java")
    generated = output / "generated"
    generated.mkdir(parents=True)
    (generated / "AndroidManifest.xml").write_text(manifest)
    for relative, text in adapted:
        destination = generated / "build-config" / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text)
    template = (HERE / "soong/Android.bp.in").read_text()
    (output / "Android.bp").write_text(template.replace("@MIN_SDK@", minimum.group(1)))
    sources = output / "src"
    sources.mkdir()
    for source in (HERE / "soong").glob("*.java"):
        shutil.copy2(source, sources / source.name)
    shutil.copy2(HERE.parent / "LICENSE", output / "LICENSE")
    (output / ".roots-owned-generated").write_text("Generated by Roots launcher.py; do not edit.\n")


def prepare(aosp):
    raw = ensure_locked(aosp)
    # Never stage under a Repo-managed project (including parent projects).
    root = ET.fromstring(raw)
    for project in root.findall("project"):
        path = project.get("path", project.get("name"))
        require(not (VENDOR_PATH == path or VENDOR_PATH.startswith(path + "/") or
                     path.startswith(VENDOR_PATH + "/")),
                "AOSP manifest owns the Roots vendor path: " + path)
    destination = aosp / VENDOR_PATH
    require(destination.resolve() == destination and not destination.is_symlink(),
            "Vendor staging path may not contain symlinks")
    require(not destination.exists() or (destination / ".roots-owned-generated").is_file(),
            "Refusing to overwrite an existing non-Roots vendor directory")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".roots-stage-", dir=destination.parent) as tmp:
        staging = Path(tmp) / "module"
        generate_module(aosp / LAUNCHER_PATH, staging)
        if destination.exists():
            shutil.rmtree(destination)
        staging.rename(destination)
    clean_source(aosp)
    print("External Soong module: " + str(destination))


def analyzer():
    executable = os.environ.get("APKANALYZER") or shutil.which("apkanalyzer")
    require(executable, "Install Android SDK command-line tools and set APKANALYZER to its executable.")
    return executable


def validate_manifest(raw):
    root = ET.fromstring(raw)
    require(root.get("package") == APP_ID, "Wrong APK package: expected " + APP_ID)
    require(not root.get(ANDROID + "sharedUserId"), "APK requests a shared UID")
    require("${" not in raw, "Unresolved manifest placeholder")
    app = root.find("application")
    require(app is not None, "APK has no application")
    homes = []
    for activity in app.findall("activity") + app.findall("activity-alias"):
        for filt in activity.findall("intent-filter"):
            actions = {a.get(ANDROID + "name") for a in filt.findall("action")}
            categories = {c.get(ANDROID + "name") for c in filt.findall("category")}
            if "android.intent.category.HOME" in categories:
                require("android.intent.action.MAIN" in actions and
                        "android.intent.category.DEFAULT" in categories,
                        "Incomplete HOME filter")
                homes.append(activity)
    require(len(homes) == 1 and homes[0].get(ANDROID + "name") == HOME_ACTIVITY and
            homes[0].get(ANDROID + "exported") == "true", "Incorrect HOME activity")
    authorities = set()
    for provider in app.findall("provider"):
        for authority in provider.get(ANDROID + "authorities", "").split(";"):
            require(authority.startswith(APP_ID + "."), "Non-isolated provider authority: " + authority)
            require(authority not in authorities, "Duplicate provider authority: " + authority)
            authorities.add(authority)
    require({APP_ID + ".settings", APP_ID + ".grid_control"} <= authorities,
            "Launcher3 providers missing or renamed; review upstream")
    for elem in root.findall("permission"):
        require(elem.get(ANDROID + "name", "").startswith(APP_ID + "."),
                "Non-isolated declared permission: " + str(elem.get(ANDROID + "name")))
    for elem in root.iter():
        for key in (ANDROID + "permission", ANDROID + "readPermission", ANDROID + "writePermission"):
            require(not elem.get(key, "").startswith(STOCK_ID + ".permission."),
                    "APK still references the stock launcher's private permission")
    print("APK manifest verified: unique HOME activity, isolated providers and permissions.")


def verify_apk(apk):
    require(apk.is_file(), "APK not found: " + str(apk))
    validate_manifest(run([analyzer(), "manifest", "print", apk], capture=True))


def locate_apk(aosp, product_out, module, source_path):
    installed = sorted(product_out.glob("*/app/" + module + "/" + module + ".apk"))
    installed += sorted(product_out.glob("*/priv-app/" + module + "/" + module + ".apk"))
    installed = list(dict.fromkeys(installed))
    if len(installed) == 1:
        return installed[0]
    require(not installed, "Ambiguous installed APK: " + module)
    intermediates = aosp / "out/soong/.intermediates" / source_path / module
    matches = sorted(intermediates.glob("android_common*/" + module + ".apk"))
    require(len(matches) == 1, "Could not identify one final APK for " + module + ": " + str(matches))
    return matches[0]


def build(args):
    aosp = args.aosp
    raw = ensure_locked(aosp)
    clean_projects(aosp)
    if args.command == "build":
        analyzer()  # Fail before an expensive AOSP build if verification is unavailable.
        prepare(aosp)
        modules = ["Launcher3", "RootsLauncher"]
    else:
        modules = ["Launcher3QuickStep" if args.command == "quickstep" else "Launcher3"]
    digest = lock_digest(raw)
    state = state_dir(aosp)
    dist = state / "dist" / digest
    dist.mkdir(parents=True, exist_ok=True)
    # Avoid reusing a different OUT_DIR inherited from another AOSP build.
    env = dict(os.environ, OUT_DIR="out", ROOTS_PRODUCT_OUT_FILE=str(state / "product-out.txt"))
    for module in modules:
        run(["bash", "-c", 'source build/envsetup.sh && lunch "$1" && m -j"$2" "$3" && '
             'get_build_var PRODUCT_OUT > "$ROOTS_PRODUCT_OUT_FILE"',
             "roots-launcher-build", args.lunch, args.jobs, module], cwd=aosp, env=env)
        product_out = Path((state / "product-out.txt").read_text().strip())
        if not product_out.is_absolute():
            product_out = aosp / product_out
        source = VENDOR_PATH if module == "RootsLauncher" else LAUNCHER_PATH
        apk = locate_apk(aosp, product_out, module, source)
        clean_source(aosp)
        require(ensure_locked(aosp) == raw, "Source lock changed during build")
        if module == "RootsLauncher":
            verify_apk(apk)
        output = dist / (module + "-aosp-test-signed.apk")
        shutil.copy2(apk, output)
        print("Built " + str(output) + " (AOSP test key; not a release artifact)")
    clean_projects(aosp)
    (dist / "manifest.lock.xml").write_text(raw)
    provenance = {
        "launcher_revision": launcher_project(raw), "manifest_sha256": digest,
        "lunch": args.lunch, "roots_revision": git(HERE, "rev-parse", "HEAD"),
        "roots_adapter_dirty": bool(git(HERE.parent, "status", "--porcelain", "--", "launcher", "LICENSE")),
        "adapter_inputs": {str(p.relative_to(HERE.parent)): hashlib.sha256(p.read_bytes()).hexdigest()
                           for p in [HERE / "launcher.py", HERE.parent / "LICENSE",
                                     *sorted((HERE / "soong").glob("*"))] if p.is_file()},
        "artifacts": {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                      for p in sorted(dist.glob("*-aosp-test-signed.apk"))},
    }
    (dist / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print("Artifacts: " + str(dist))


def sign(args):
    apk = args.apk.resolve()
    verify_apk(apk)
    tool = os.environ.get("APKSIGNER") or shutil.which("apksigner")
    require(tool, "Set APKSIGNER to Android SDK build-tools/apksigner")
    require(args.keystore.is_file(), "Local signing keystore not found")
    output = args.output.resolve()
    require(output != apk and not output.exists(), "Choose a new output file; never overwrite an APK")
    output.parent.mkdir(parents=True, exist_ok=True)
    # No key or password goes in the repo, environment, or command line.
    with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
        candidate = Path(tmp) / "RootsLauncher.apk"
        run([tool, "sign", "--ks", args.keystore, "--ks-key-alias", args.alias,
             "--out", candidate, apk])
        run([tool, "verify", "--verbose", "--print-certs", candidate])
        verify_apk(candidate)
        candidate.rename(output)
    print("Locally signed APK: " + str(output))


class AdapterTests(unittest.TestCase):
    def test_manifest(self):
        raw = ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
               'package="com.android.launcher3"><application android:backupAgent=".LauncherBackupAgent">'
               '<activity android:name=".Launcher"><intent-filter>'
               '<action android:name="android.intent.action.MAIN"/>'
               '<category android:name="android.intent.category.HOME"/>'
               '<category android:name="android.intent.category.DEFAULT"/>'
               '</intent-filter></activity><provider android:name=".LauncherProvider" '
               'android:authorities="${applicationId}.settings"/>'
               '<provider android:name=".GridProvider" android:authorities="${packageName}.grid_control"/>'
               '</application></manifest>')
        result = make_manifest(raw)
        validate_manifest(result)
        root = ET.fromstring(result)
        self.assertEqual(root.find("application").get(ANDROID + "backupAgent"), STOCK_ID + ".LauncherBackupAgent")
        self.assertIn('package="com.android.launcher3"', raw)
        with self.assertRaises(RuntimeError):
            make_manifest(raw.replace(".Launcher\"", ".OtherLauncher\""))
        with self.assertRaises(RuntimeError):
            validate_manifest(result.replace(APP_ID + ".settings", STOCK_ID + ".settings"))

    def test_config(self):
        raw = 'package com.android.launcher3; public static final String APPLICATION_ID = "com.android.launcher3";'
        result = make_build_config(raw)
        self.assertIn('APPLICATION_ID = "' + APP_ID + '"', result)
        self.assertIn("package com.android.launcher3;", result)
        with self.assertRaises(RuntimeError):
            make_build_config("class BuildConfig {}")

    def test_manifest_lock(self):
        raw = '<manifest><project name="platform/packages/apps/Launcher3" path="packages/apps/Launcher3" revision="abc"/></manifest>'
        self.assertEqual(launcher_project(raw), "abc")
        with self.assertRaises(RuntimeError):
            launcher_project(raw.replace("platform/packages/apps/Launcher3", "some/fork"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aosp", type=Path, default=Path.home() / "aosp-roots-launcher")
    parser.add_argument("--branch", help="Official manifest release ref (new workspace only)")
    parser.add_argument("--jobs", type=int, default=max(1, min(os.cpu_count() or 4, 8)))
    parser.add_argument("--lunch", default=os.environ.get("ROOTS_AOSP_LUNCH", DEFAULT_LUNCH))
    subs = parser.add_subparsers(dest="command", required=True)
    for command in ("sync", "status", "prepare", "baseline", "build", "quickstep", "self-test"):
        subs.add_parser(command)
    verify = subs.add_parser("verify-apk")
    verify.add_argument("apk", type=Path)
    signing = subs.add_parser("sign")
    signing.add_argument("apk", type=Path)
    signing.add_argument("--keystore", type=Path, required=True)
    signing.add_argument("--alias", required=True)
    signing.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.aosp = args.aosp.expanduser().resolve()
    require(args.jobs > 0, "--jobs must be positive")
    if args.command == "self-test":
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(AdapterTests))
        return 0 if result.wasSuccessful() else 1
    if args.command == "sync":
        sync(args)
    elif args.command == "status":
        status(args)
    elif args.command == "prepare":
        prepare(args.aosp)
    elif args.command == "verify-apk":
        verify_apk(args.apk)
    elif args.command == "sign":
        sign(args)
    else:
        build(args)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, OSError, ValueError, subprocess.CalledProcessError, ET.ParseError) as error:
        print("Roots Launcher: " + str(error), file=sys.stderr)
        sys.exit(1)
