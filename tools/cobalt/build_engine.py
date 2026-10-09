"""Build an INTEGRATION APK from a provisioned Linux Cobalt Chromium checkout.

Never downloads hundreds of GB, installs host packages, or silently builds the
non-Chromium document engine. Requires existing extension signing keys.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def run(*args, cwd=None):
    return subprocess.run(args, cwd=cwd, check=True, text=True, capture_output=True).stdout.strip()


def main():
    if platform.system() != "Linux":
        raise RuntimeError("The Chromium Android build requires a provisioned Linux runner")
    baseline = json.loads((ROOT / "tools/cobalt/baseline.json").read_text())
    upstream = Path(os.environ["COBALT_REPO"]).resolve()
    src = Path(os.environ["COBALT_SRC"]).resolve()
    out = Path(os.environ.get("COBALT_OUT", str(src / "out/Default"))).resolve()
    if not out.is_relative_to(src) or out == src:
        raise ValueError("COBALT_OUT must be inside the isolated Chromium checkout")
    if run("git", "rev-parse", "HEAD", cwd=upstream) != baseline["commit"]:
        raise RuntimeError("Cobalt source is not at the pinned baseline")
    if run("git", "describe", "--tags", "--exact-match", "HEAD", cwd=src) != baseline["chromiumTag"]:
        raise RuntimeError("Wrong Chromium base")
    args = (out / "args.gn").read_text()
    for pattern in (r'(?m)^\s*is_desktop_android\s*=\s*true\s*$', r'(?m)^\s*target_os\s*=\s*"android"\s*$', r'(?m)^\s*target_cpu\s*=\s*"arm64"\s*$'):
        if not re.search(pattern, args):
            raise RuntimeError("Missing required GN setting: " + pattern)
    keys = {"dola30-original": Path(os.environ["DOLA30_CRX_KEY"]), "fingerprint-privacy": Path(os.environ["DOLA_PRIVACY_CRX_KEY"])}
    if not all(p.is_file() for p in keys.values()):
        raise RuntimeError("Provide persistent CRX signing keys on the runner; keys are never created or uploaded by this job")
    output = ROOT / ".tooling" / ("cobalt-build-" + str(time.time_ns()))
    subprocess.run(["python3", str(ROOT / "tools/package_extensions.py"), str(output)], check=True)
    entries = []
    for group, key in keys.items():
        crx = output / ("dola-" + group + ".crx")
        result = run("python3", str(upstream / "tools/assets/pack-crx.py"), str(output / group), "--key", str(key), "--out", str(crx))
        ids = re.findall(r"(?m)^id\s+([a-p]{32})$", result)
        if len(ids) != 1 or "signature OK" not in result:
            raise RuntimeError("CRX signature/id verification did not complete")
        version = json.loads((output / group / "manifest.json").read_text())["version"]
        entries.append({"name": crx.name, "id": ids[0], "version": version, "crx": str(crx)})
    spec = importlib.util.spec_from_file_location("stage_extensions", ROOT / "tools/cobalt/stage_extensions.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    hashes = module.stage(src, entries)
    started = time.time()
    subprocess.run(["gn", "gen", str(out)], cwd=src, check=True)
    subprocess.run(["autoninja", "-C", str(out), "-j", os.environ.get("COBALT_JOBS", "4"), "chrome_public_apk"], cwd=src, check=True)
    apk = out / "apks/ChromePublic.apk"
    if not apk.is_file() or apk.stat().st_mtime < started:
        raise RuntimeError("No fresh APK; stale build output is not success")
    with zipfile.ZipFile(apk) as archive:
        if not any(n.startswith("lib/arm64-v8a/") and n.endswith(".so") for n in archive.namelist()):
            raise RuntimeError("APK lacks native arm64 engine")
        for name, expected in hashes.items():
            info = archive.getinfo("assets/" + name)
            if info.compress_type != zipfile.ZIP_STORED or hashlib.sha256(archive.read(info)).hexdigest() != expected:
                raise RuntimeError("Bundled CRX missing, compressed, or stale: " + name)
    # Deliberately not named Doulala replacement: original Android shell is retained.
    import shutil
    shutil.copy2(apk, output / "cobalt-extension-integration.apk")
    report = {"baseline": baseline, "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "bundled": hashes,
              "status": "built-not-device-verified", "notComplete": ["Doulala UI/profile migration", "Android extension installation/runtime validation"]}
    (output / "build-result.json").write_text(json.dumps(report, indent=2))
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as dest:
            dest.write("directory=" + str(output) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
