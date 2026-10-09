"""Extend Cobalt's existing external-extension stager, not just APK assets.

Run AFTER the pinned Cobalt patch series on an isolated Chromium checkout.
No policy forces these extensions on; users can disable/uninstall them.
All anchors are validated before edits. Version/hash stamp invalidates old staging.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import time


def atomic_write(path, data):
    temp = path.with_name(path.name + ".dola.tmp")
    temp.write_bytes(data)
    temp.replace(path)


def stage(src, entries):
    src = Path(src).resolve()
    directory = src / "chrome/browser/cobalt"
    cc_path = directory / "bundled_extensions.cc"
    gn_path = directory / "extensions/BUILD.gn"
    cc, gn = cc_path.read_text(encoding="utf-8"), gn_path.read_text(encoding="utf-8")
    original_cc, original_gn = cc, gn
    cc = re.sub(r"\n// DOLA-BUNDLE-BEGIN\n.*?// DOLA-BUNDLE-END\n", "", cc, flags=re.S)
    gn = re.sub(r"\n  # DOLA-BUNDLE-BEGIN\n.*?  # DOLA-BUNDLE-END\n", "", gn, flags=re.S)
    cc_anchor = "constexpr auto kBundledExtensions = std::to_array<BundledExtension>({\n"
    gn_anchor = "  sources = [\n"
    if cc.count(cc_anchor) != 1 or gn.count(gn_anchor) != 1 or "disable_compression = true" not in gn:
        raise ValueError("Pinned Cobalt stager anchors changed; refusing to guess")
    lines, assets, hashes = [], [], {}
    seen_ids = set()
    for entry in entries:
        name, extension_id, version = entry["name"], entry["id"], entry["version"]
        if not re.fullmatch(r"dola-[a-z0-9-]+\.crx", name):
            raise ValueError("Invalid CRX asset name")
        if not re.fullmatch(r"[a-p]{32}", extension_id) or not re.fullmatch(r"\d+(\.\d+){0,3}", version):
            raise ValueError("Invalid extension ID or version")
        if extension_id in seen_ids:
            raise ValueError("Duplicate extension signing identity")
        seen_ids.add(extension_id)
        data = Path(entry["crx"]).read_bytes()
        if data[:8] != b"Cr24\x03\x00\x00\x00":
            raise ValueError("Expected a verified CRX3")
        if name in hashes:
            raise ValueError("Duplicate extension asset")
        hashes[name] = hashlib.sha256(data).hexdigest()
        assets.append((directory / "extensions" / name, data))
        lines.append(f'    {{"assets/{name}", "{name}", "{extension_id}", "{version}"}},')
    if not entries:
        raise ValueError("No extensions supplied")
    cc = cc.replace(cc_anchor, cc_anchor + "\n// DOLA-BUNDLE-BEGIN\n" + "\n".join(lines) + "\n// DOLA-BUNDLE-END\n")
    gn = gn.replace(gn_anchor, gn_anchor + "\n  # DOLA-BUNDLE-BEGIN\n" + "\n".join(f'    "{e["name"]}",' for e in entries) + "\n  # DOLA-BUNDLE-END\n")
    stamp = re.compile(r'(constexpr char kStampContents\[\] = ")([^"\n]+)(";)')
    if len(stamp.findall(cc)) != 1:
        raise ValueError("Staging version stamp missing or ambiguous")
    identities = [{k: e[k] for k in ("name", "id", "version")} for e in entries]
    digest = hashlib.sha256(json.dumps(identities, sort_keys=True).encode() + json.dumps(hashes, sort_keys=True).encode()).hexdigest()[:20]
    cc = stamp.sub(lambda m: m[1] + m[2].split("+dola-")[0] + "+dola-" + digest + m[3], cc)
    if cc == original_cc and gn == original_gn and all(p.exists() and p.read_bytes() == data for p, data in assets):
        return hashes
    backup = src / ".dola-backups" / str(time.time_ns())
    backup.mkdir(parents=True)
    shutil.copy2(cc_path, backup / "bundled_extensions.cc")
    shutil.copy2(gn_path, backup / "BUILD.gn")
    for dest, data in assets:
        if dest.exists():
            shutil.copy2(dest, backup / dest.name)
        atomic_write(dest, data)
    atomic_write(gn_path, gn.encode())
    atomic_write(cc_path, cc.encode())
    return hashes


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--src", required=True)
    parser.add_argument("--entries", required=True)
    args = parser.parse_args()
    print(json.dumps(stage(args.src, json.loads(Path(args.entries).read_text(encoding="utf-8"))), indent=2))
