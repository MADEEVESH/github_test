#!/usr/bin/env python3
"""Verify the packaged release APK, including the BLE-only network boundary.

Requires Python 3, Java and Android SDK build-tools on the build machine.
Debug/profile APKs intentionally fail this check: Flutter enables network tooling.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--sdk", default=os.getenv("ANDROID_HOME") or os.getenv("ANDROID_SDK_ROOT"))
    args = parser.parse_args()
    require(args.apk.is_file(), "APK file is missing.")
    require(args.sdk, "Set ANDROID_HOME or pass --sdk.")
    candidates = [path for path in (Path(args.sdk) / "build-tools").iterdir()
                  if (path / "aapt").is_file() and (path / "apksigner").is_file()]
    require(candidates, "Android SDK build-tools with aapt, apksigner and zipalign are required.")
    tools = max(candidates, key=lambda path: tuple(int(x) for x in re.findall(r"\d+", path.name)))

    def run(tool, *arguments):
        return subprocess.run([str(tools / tool), *map(str, arguments)], check=True,
                              text=True, capture_output=True).stdout

    with zipfile.ZipFile(args.apk) as archive:
        require(archive.testzip() is None, "APK ZIP integrity check failed.")
        libraries = {name.split("/")[1] for name in archive.namelist()
                     if name.startswith("lib/") and name.endswith("/libflutter.so")}
        require(libraries == {"armeabi-v7a", "arm64-v8a", "x86_64"},
                "The release download must include all three supported Android ABIs.")
        for name in archive.namelist():
            if not re.match(r"lib/(arm64-v8a|x86_64)/[^/]+\.so$", name):
                continue
            data = archive.read(name)
            require(data[:6] == b"\x7fELF\x02\x01", "Expected a little-endian ELF64 library: " + name)
            offset = struct.unpack_from("<Q", data, 32)[0]
            entry_size, entries = struct.unpack_from("<HH", data, 54)
            require(entry_size >= 56, "Invalid ELF program header: " + name)
            for index in range(entries):
                entry = offset + index * entry_size
                if struct.unpack_from("<I", data, entry)[0] == 1:  # PT_LOAD
                    require(struct.unpack_from("<Q", data, entry + 48)[0] >= 16384,
                            "Native library lacks 16 KiB load alignment: " + name)
    badging = run("aapt", "dump", "badging", args.apk)
    require("package: name='dev.offlinechat.offlinechat'" in badging, "Unexpected APK package.")
    require("sdkVersion:'29'" in badging, "Unexpected Android minimum SDK.")
    permissions = run("aapt", "dump", "permissions", args.apk)
    require("android.permission.INTERNET" not in permissions,
            "Release APK requests Internet permission; messaging must remain BLE-only.")
    for permission in ("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE",
                       "FOREGROUND_SERVICE_CONNECTED_DEVICE"):
        require("android.permission." + permission in permissions, "Missing " + permission)
    print(run("apksigner", "verify", "--verbose", "--print-certs", args.apk).strip())
    run("zipalign", "-c", "-P", "16", "4", args.apk)
    print("APK integrity, universal ABIs, SDK, offline permissions, signature, ELF and ZIP 16 KiB alignment passed.")
    digest = hashlib.sha256()
    with args.apk.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    print(digest.hexdigest(), args.apk.name)


if __name__ == "__main__":
    main()
