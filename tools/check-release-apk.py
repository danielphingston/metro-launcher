#!/usr/bin/env python3
"""Checks a minified release APK for classes that are created by reflection but lost the
constructor they need. R8 cannot see reflective calls, so when a keep rule stops covering one
(as happened to WorkManager's Room database in v0.7.16) the app only fails at runtime.

Checks:
  * every class named in the merged manifest (application, activities, services, receivers,
    providers, androidx.startup initializers) exists and has a public no-arg constructor
  * every concrete Room database (RoomDatabase subclass) has a no-arg constructor
  * every concrete WorkManager worker has the (Context, WorkerParameters) constructor

Usage: tools/check-release-apk.py app-release.apk [mapping.txt]   (needs ANDROID_HOME build-tools)
Pass R8's mapping.txt so renamed base classes (RoomDatabase, ListenableWorker) are recognised.
"""
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile

NOARG = "()V"
WORKER_CTOR = "(Landroid/content/Context;Landroidx/work/WorkerParameters;)V"


def tool(name):
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "/opt/android-sdk"
    found = sorted(glob.glob(os.path.join(home, "build-tools", "*", name)))
    if not found:
        sys.exit(f"{name} not found under {home}/build-tools")
    return found[-1]


def read_dex(apk):
    """Returns {descriptor: {"super": str, "abstract": bool, "ctors": set(signatures)}}."""
    classes = {}
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
        dexes = [n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
        for n in dexes:
            z.extract(n, tmp)
        out = subprocess.run([tool("dexdump")] + [os.path.join(tmp, n) for n in dexes],
                             capture_output=True, text=True, check=True).stdout
    cur = None
    method = None
    for line in out.splitlines():
        s = line.strip()
        if s.startswith("Class descriptor"):
            desc = s.split("'")[1]
            cur = classes.setdefault(desc, {"super": None, "abstract": False, "ctors": set()})
            method = None
        elif cur is None:
            continue
        elif s.startswith("Access flags") and method is None and cur["super"] is None:
            cur["abstract"] = "ABSTRACT" in s or "INTERFACE" in s
        elif s.startswith("Superclass"):
            cur["super"] = s.split("'")[1] if "'" in s else None
        elif s.startswith("name") and "'" in s:
            method = s.split("'")[1]
        elif s.startswith("type") and method == "<init>":
            cur["ctors"].add(s.split("'")[1])
            method = None
    return classes


def deobfuscate(classes, mapping):
    """Renames obfuscated descriptors back to their source names using R8's mapping.txt."""
    names = {}
    with open(mapping) as f:
        for line in f:
            m = re.match(r"(\S+) -> (\S+):$", line.rstrip())
            if m:
                names["L" + m.group(2).replace(".", "/") + ";"] = "L" + m.group(1).replace(".", "/") + ";"
    out = {}
    for desc, c in classes.items():
        c["super"] = names.get(c["super"], c["super"])
        c["ctors"] = {re.sub(r"L[^;]+;", lambda t: names.get(t.group(0), t.group(0)), sig) for sig in c["ctors"]}
        out[names.get(desc, desc)] = c
    return out


def manifest_classes(apk):
    out = subprocess.run([tool("aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
                         capture_output=True, text=True, check=True).stdout
    pkg = re.search(r'package="([^"]+)"', out).group(1)
    names = []
    element = None
    for line in out.splitlines():
        s = line.strip()
        m = re.match(r"E: (\S+)", s)
        if m:
            element = m.group(1)
            continue
        m = re.search(r'android:name\(0x01010003\)="([^"]+)"', s)
        if not m:
            continue
        value = m.group(1)
        if element in ("application", "activity", "service", "receiver", "provider"):
            names.append(value)
    # androidx.startup lists initializers as <meta-data android:name="Class" android:value="androidx.startup"/>
    for m in re.finditer(r'E: meta-data.*?android:name\(0x01010003\)="([^"]+)".*?android:value\(0x01010024\)="([^"]+)"',
                         out, re.S):
        if m.group(2) == "androidx.startup" and m.group(1) not in names:
            names.append(m.group(1))
    full = []
    for n in names:
        if n.startswith("."):
            n = pkg + n
        full.append("L" + n.replace(".", "/") + ";")
    return full


def is_subclass(classes, desc, base):
    seen = set()
    while desc and desc not in seen:
        if desc == base:
            return True
        seen.add(desc)
        desc = classes.get(desc, {}).get("super")
    return False


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    apk = sys.argv[1]
    classes = read_dex(apk)
    if len(sys.argv) == 3:
        classes = deobfuscate(classes, sys.argv[2])
    problems = []

    for desc in manifest_classes(apk):
        c = classes.get(desc)
        if c is None:
            # Framework or optional classes declared by libraries may live outside the APK.
            if not desc.startswith(("Landroid/", "Ljava/")):
                problems.append(f"manifest class missing from dex: {desc}")
        elif NOARG not in c["ctors"]:
            problems.append(f"manifest class has no no-arg constructor: {desc}")

    for desc, c in classes.items():
        if c["abstract"]:
            continue
        # Room keeps the names of its generated *_Impl databases, so they are found even without a mapping.
        room = is_subclass(classes, c["super"], "Landroidx/room/RoomDatabase;") or (
            desc.endswith("_Impl;") and (c["super"] or "").endswith("Database;"))
        if room and NOARG not in c["ctors"]:
            problems.append(f"Room database has no no-arg constructor: {desc}")
        if is_subclass(classes, c["super"], "Landroidx/work/ListenableWorker;") and WORKER_CTOR not in c["ctors"]:
            problems.append(f"worker has no (Context, WorkerParameters) constructor: {desc}")

    if problems:
        print("Release APK check FAILED:")
        for p in problems:
            print("  " + p)
        sys.exit(1)
    print(f"Release APK check passed ({len(classes)} classes scanned).")


if __name__ == "__main__":
    main()
