"""Verify the built APK, not just source files, retains the supplied extension."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def verify(apk):
    apk = Path(apk)
    original_manifests = [
        ("dola30-original", ROOT / "app/src/main/assets/dola30-original/SOURCE-MANIFEST.json"),
        ("dola30-user-source-v1.6.9", ROOT / "app/src/main/assets/dola30-user-source-v1.6.9/SOURCE-MANIFEST.json"),
    ]
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    sidecar = apk.with_suffix(apk.suffix + ".sha256")
    if sidecar.exists() and digest != sidecar.read_text(encoding="utf-8").split()[0]:
        raise ValueError("APK SHA256 differs from build record")
    # AAPT stores UTF-8 paths but may omit ZIP's UTF-8 flag (e.g. Chinese docs).
    with zipfile.ZipFile(apk, metadata_encoding="utf-8") as archive:
        verified = {}
        for asset_dir, manifest_path in original_manifests:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            for entry in manifest["files"]:
                name = "assets/" + asset_dir + "/" + entry["path"]
                data = archive.read(name)
                if len(data) != entry["bytes"] or hashlib.sha256(data).hexdigest() != entry["sha256"]:
                    raise ValueError("Original file changed inside APK: " + name)
                verified[asset_dir] = verified.get(asset_dir, 0) + 1
        if any(".bak." in name or name.endswith(".pem") for name in archive.namelist()):
            raise ValueError("APK contains backups or private key files")
    return {
        "apkSha256": digest,
        "originalFilesVerified": verified.get("dola30-original", 0),
        "additionalSourceFilesVerified": {key: count for key, count in verified.items() if key != "dola30-original"},
        "runtimeVerified": False,
    }


if __name__ == "__main__":
    print(json.dumps(verify(sys.argv[1]), indent=2))
