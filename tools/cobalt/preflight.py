"""Read-only readiness checks for an already provisioned Cobalt build host."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
REQUIRED_GN = {
    "target_os": '"android"', "target_cpu": '"arm64"',
    "is_desktop_android": "true", "enable_jni_multiplexing": "false",
}


def gn_errors(text):
    errors = []
    for key, expected in REQUIRED_GN.items():
        values = re.findall(r"(?m)^\s*" + key + r"\s*=\s*([^#\n]+)", text)
        if [v.strip() for v in values] != [expected]:
            errors.append(key)
    return errors


def inspect(env=None):
    env = os.environ if env is None else env
    checks = []

    def check(name, ok, detail):
        checks.append({"name": name, "ok": bool(ok), "detail": detail})

    check("host", platform.system() == "Linux" and platform.machine() in ("x86_64", "AMD64"),
          "Requires Linux x64; this check never installs host packages")
    for tool in ("git", "gn", "autoninja", "openssl", "python3", "node"):
        check("tool:" + tool, shutil.which(tool) is not None, "Must be available in runner PATH")
    check("python:cryptography", importlib.util.find_spec("cryptography") is not None,
          "Required by the upstream CRX signature verifier")
    jobs = env.get("COBALT_JOBS", "4")
    check("jobs", jobs.isdigit() and 1 <= int(jobs) <= 64, "COBALT_JOBS must be 1..64")
    baseline = json.loads((ROOT / "tools/cobalt/baseline.json").read_text(encoding="utf-8"))
    for name, command, expected in (
        ("COBALT_REPO", ["git", "rev-parse", "HEAD"], baseline["commit"]),
        ("COBALT_SRC", ["git", "describe", "--tags", "--exact-match", "HEAD"], baseline["chromiumTag"]),
    ):
        value = env.get(name)
        path = Path(value).resolve() if value else None
        exists = path is not None and path.is_dir()
        check(name, exists, "Set an existing dedicated source directory")
        if exists:
            try:
                result = subprocess.run(command, cwd=path, capture_output=True, text=True, timeout=30, check=True)
                check(name + ":pin", result.stdout.strip() == expected, "Must match tools/cobalt/baseline.json")
            except (OSError, subprocess.SubprocessError):
                check(name + ":pin", False, "Cannot verify source revision")
    src_value = env.get("COBALT_SRC")
    if src_value and Path(src_value).is_dir():
        src = Path(src_value).resolve()
        out = Path(env.get("COBALT_OUT", str(src / "out/Default"))).resolve()
        contained = out != src and out.is_relative_to(src)
        check("output:contained", contained, "Output must be inside the dedicated Chromium source tree")
        args = out / "args.gn"
        if contained and args.is_file():
            errors = gn_errors(args.read_text(encoding="utf-8"))
            check("gn:settings", not errors, "Missing/duplicate/wrong settings: " + ", ".join(errors))
            if re.search(r'cc_wrapper\s*=\s*"ccache"', args.read_text(encoding="utf-8")):
                check("tool:ccache", shutil.which("ccache") is not None, "args.gn requests ccache")
        else:
            check("gn:settings", False, "Prepare args.gn using the pinned upstream build scripts")
        for relative in ("chrome/browser/cobalt/bundled_extensions.cc", "chrome/browser/cobalt/extensions/BUILD.gn",
                         "chrome/browser/cobalt/extensions/ublock.crx", "chrome/browser/cobalt/extensions/pdfjs.crx"):
            check("source:" + relative, (src / relative).is_file(), "Apply the complete pinned upstream patch series first")
        check("disk:headroom", shutil.disk_usage(src).free >= 64 * 1024**3,
              "Project guard: at least 64 GiB free AFTER provisioning; not a fresh-checkout sizing estimate")
    key_paths = []
    for name in ("DOLA30_CRX_KEY", "DOLA_PRIVACY_CRX_KEY"):
        value = env.get(name)
        key = Path(value).resolve() if value else None
        exists = key is not None and key.is_file()
        check(name, exists, "Provide a persistent RSA key; paths and contents are not logged")
        if exists:
            key_paths.append(key)
            if platform.system() == "Linux":
                check(name + ":private", key.stat().st_mode & 0o077 == 0, "Private key must not be group/world accessible")
    if len(key_paths) == 2:
        check("keys:distinct", key_paths[0].read_bytes() != key_paths[1].read_bytes(),
              "Different extensions require different signing identities")
    return {"ready": all(c["ok"] for c in checks), "checks": checks,
            "scope": "build prerequisites only; not compilation or device validation"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report = inspect()
    text = json.dumps(report, indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        # Do not overwrite evidence from a previous run.
        with args.report.open("x", encoding="utf-8") as output:
            output.write(text)
    print(text)
    raise SystemExit(0 if report["ready"] else 2)
