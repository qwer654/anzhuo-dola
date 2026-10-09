"""Stage and zip only declared extension sources, preserving Dola30 bytes."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PRIVACY_FILES = ("manifest.json", "service-worker.js", "content-main.js", "bridge.js", "popup.html", "popup.js")


def package(output):
    # Use a new destination so old or unexpected files can never enter a CRX.
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    assets = ROOT / "app/src/main/assets"
    original = assets / "dola30-original"
    manifest = json.loads((original / "SOURCE-MANIFEST.json").read_text(encoding="utf-8"))
    assert manifest["preservedUnmodified"] is True
    sources = {}
    for entry in manifest["files"]:
        name = entry["path"]
        source = (original / name).resolve()
        if not source.is_relative_to(original.resolve()):
            raise ValueError("Source manifest escapes its directory")
        data = source.read_bytes()
        if len(data) != entry["bytes"] or hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise ValueError("Original source changed: " + name)
        sources[name] = source
    groups = {"dola30-original": sources, "fingerprint-privacy": {name: assets / "fingerprint-privacy" / name for name in PRIVACY_FILES}}
    result = {}
    for group, files in groups.items():
        stage = output / group
        stage.mkdir()
        hashes = {}
        with zipfile.ZipFile(output / (group + ".zip"), "w", zipfile.ZIP_DEFLATED) as archive:
            for name, source in sorted(files.items()):
                dest = stage / name
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, dest)
                data = dest.read_bytes()
                info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                archive.writestr(info, data)
                hashes[name] = hashlib.sha256(data).hexdigest()
        result[group] = {"files": hashes, "zipSha256": hashlib.sha256((output / (group + ".zip")).read_bytes()).hexdigest()}
    (output / "checksums.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    return result


if __name__ == "__main__":
    print(json.dumps(package(sys.argv[1]), indent=2))
