"""Test real pinned Cobalt templates and signed CRXs, without claiming an APK build.

Disposable test keys never leave the temporary directory. The only exported file
is a JSON report. No Chromium checkout or original extension is modified.
"""
import argparse
import ast
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def upstream_constants(script):
    # Read only literal constants, never execute the patch script.
    values = {}
    for node in ast.parse(script.read_text(encoding="utf-8")).body:
        if not isinstance(node, ast.Assign) or len(node.targets) != 1 or not isinstance(node.targets[0], ast.Name):
            continue
        name, value = node.targets[0].id, node.value
        if isinstance(value, ast.Constant) and isinstance(value.value, str):
            values[name] = value.value
        elif name == "STAGER_CC" and isinstance(value, ast.BinOp) and isinstance(value.op, ast.Mod):
            mapping = {ast.literal_eval(k): values[v.id] for k, v in zip(value.right.keys, value.right.values)}
            values[name] = ast.literal_eval(value.left) % mapping
    return values


def check(upstream):
    baseline = json.loads((ROOT / "tools/cobalt/baseline.json").read_text(encoding="utf-8"))
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=upstream, text=True).strip()
    if actual != baseline["commit"]:
        raise ValueError("Upstream checkout is not at the pinned commit")
    constants = upstream_constants(upstream / "tools/patches/cobalt-bundle-ublock.py")
    pack = load("dola_pack", ROOT / "tools/package_extensions.py")
    stager = load("dola_stage", ROOT / "tools/cobalt/stage_extensions.py")
    signer = upstream / "tools/assets/pack-crx.py"
    verifier = load("upstream_crx", signer)
    with tempfile.TemporaryDirectory(prefix="dola-cobalt-contract-") as tmp:
        work = Path(tmp)
        packages = work / "packages"
        pack.package(packages)
        entries, evidence = [], []
        for group in ("dola30-original", "fingerprint-privacy"):
            crx = work / ("dola-" + group + ".crx")
            result = subprocess.run([sys.executable, str(signer), str(packages / group),
                                     "--key", str(work / (group + ".pem")), "--out", str(crx)],
                                    capture_output=True, text=True, check=True)
            ids = re.findall(r"(?m)^id\s+([a-p]{32})$", result.stdout)
            if len(ids) != 1 or "signature OK" not in result.stdout:
                raise ValueError("Real CRX signer did not verify the signature")
            with zipfile.ZipFile(crx) as archive:
                expected = {p.relative_to(packages / group).as_posix(): p.read_bytes()
                            for p in (packages / group).rglob("*") if p.is_file()}
                if set(archive.namelist()) != set(expected) or any(archive.read(n) != b for n, b in expected.items()):
                    raise ValueError("Signed CRX changed extension bytes")
            # Alter signed ZIP payload; real crypto verification must reject it.
            data = bytearray(crx.read_bytes())
            offset = 12 + struct.unpack("<I", data[8:12])[0]
            data[offset] ^= 1
            tampered = work / "tampered.crx"
            tampered.write_bytes(data)
            if verifier.verify(tampered):
                raise ValueError("Tampered signature accepted")
            version = json.loads((packages / group / "manifest.json").read_text(encoding="utf-8"))["version"]
            entries.append({"name": crx.name, "id": ids[0], "version": version, "crx": str(crx)})
            evidence.append({"group": group, "filesVerified": len(expected), "signatureVerified": True,
                             "tamperRejected": True})
        src = work / "src"
        directory = src / "chrome/browser/cobalt"
        (directory / "extensions").mkdir(parents=True)
        cc, gn = directory / "bundled_extensions.cc", directory / "extensions/BUILD.gn"
        cc.write_text(constants["STAGER_CC"], encoding="utf-8")
        gn.write_text(constants["ASSET_BUILD_GN"], encoding="utf-8")
        untouched = cc.read_bytes(), gn.read_bytes()
        try:
            stager.stage(src, [entries[0], {**entries[1], "id": entries[0]["id"]}])
        except ValueError:
            if untouched != (cc.read_bytes(), gn.read_bytes()):
                raise ValueError("Rejected duplicate identity still changed upstream source")
        else:
            raise ValueError("Duplicate signing identities were accepted")
        hashes = stager.stage(src, entries)
        before = cc.read_bytes(), gn.read_bytes()
        if stager.stage(src, entries) != hashes or before != (cc.read_bytes(), gn.read_bytes()):
            raise ValueError("Staging is not idempotent against real upstream templates")
        text = cc.read_text(encoding="utf-8")
        for name in ("ublock.crx", "pdfjs.crx"):
            if f'"assets/{name}"' not in text or f'"{name}"' not in gn.read_text(encoding="utf-8"):
                raise ValueError("Upstream bundled extension removed")
        for entry in entries:
            if text.count('"' + entry["id"] + '"') != 1:
                raise ValueError("Extension identity missing or duplicated in install table")
            if hashlib.sha256((gn.parent / entry["name"]).read_bytes()).hexdigest() != hashes[entry["name"]]:
                raise ValueError("Staged CRX bytes differ")
        return {"ok": True, "upstreamCommit": actual, "extensions": evidence,
                "realUpstreamTemplates": True, "idempotent": True, "existingBundlesPreserved": True,
                "notVerified": ["Chromium compilation", "Android installation", "extension runtime", "Doulala UI migration"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    # Reserve a new report before tests, preventing reuse of stale success.
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open("x", encoding="utf-8") as dest:
        dest.write('{"ok": false, "status": "running"}\n')
    try:
        result = check(args.upstream.resolve())
    except Exception:
        args.report.write_text('{"ok": false, "status": "failed"}\n', encoding="utf-8")
        raise
    args.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
