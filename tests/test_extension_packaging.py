import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


pack = load("pack", "tools/package_extensions.py")
stager = load("stager", "tools/cobalt/stage_extensions.py")
apk_verify = load("apk_verify", "tools/verify_apk_assets.py")


class Packaging(unittest.TestCase):
    def test_apk_verification_checks_built_bytes_and_rejects_tampering(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "fixture.apk"
            sources = {
                "dola30-original": ROOT / "app/src/main/assets/dola30-original",
                "dola30-user-source-v1.6.9": ROOT / "app/src/main/assets/dola30-user-source-v1.6.9",
            }
            manifests = {name: json.loads((source / "SOURCE-MANIFEST.json").read_text(encoding="utf-8")) for name, source in sources.items()}
            for tamper in (False, True):
                with zipfile.ZipFile(apk, "w") as archive:
                    for name, source in sources.items():
                        for entry in manifests[name]["files"]:
                            data = (source / entry["path"]).read_bytes()
                            if tamper and name == "dola30-original" and entry["path"] == "manifest.json":
                                data += b" "
                            archive.writestr("assets/" + name + "/" + entry["path"], data)
                if tamper:
                    with self.assertRaises(ValueError):
                        apk_verify.verify(apk)
                else:
                    result = apk_verify.verify(apk)
                    self.assertEqual(result["originalFilesVerified"], len(manifests["dola30-original"]["files"]))
                    self.assertEqual(result["additionalSourceFilesVerified"], {"dola30-user-source-v1.6.9": len(manifests["dola30-user-source-v1.6.9"]["files"])})

    def test_packages_are_complete_deterministic_and_preserve_sources(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "a", Path(tmp) / "b"
            self.assertEqual(pack.package(a), pack.package(b))
            for group in ("dola30-original", "fingerprint-privacy"):
                with zipfile.ZipFile(a / (group + ".zip")) as archive:
                    names = archive.namelist()
                    self.assertFalse(any(".bak" in n or n.endswith(".pem") for n in names))
                    manifest = json.loads(archive.read("manifest.json"))
                    refs = list(manifest.get("icons", {}).values())
                    refs += list(manifest.get("action", {}).get("default_icon", {}).values())
                    refs += [manifest["background"]["service_worker"]]
                    for script in manifest["content_scripts"]:
                        refs += script["js"]
                    for resource in manifest.get("web_accessible_resources", []):
                        refs += resource["resources"]
                    for ref in refs:
                        self.assertIn(ref, names)
                    for name in names:
                        self.assertEqual(archive.read(name), (ROOT / "app/src/main/assets" / group / name).read_bytes())
            with self.assertRaises(FileExistsError):
                pack.package(a)

    def fixture(self, tmp):
        src = Path(tmp)
        directory = src / "chrome/browser/cobalt"
        (directory / "extensions").mkdir(parents=True)
        cc = directory / "bundled_extensions.cc"
        gn = directory / "extensions/BUILD.gn"
        cc.write_text('constexpr auto kBundledExtensions = std::to_array<BundledExtension>({\n    {"assets/ublock.crx", "ublock.crx", "existing-id", "1.0"},\n});\nconstexpr char kStampContents[] = "upstream-1.0";\n')
        gn.write_text('android_assets("bundled_extension_assets") {\n  sources = [\n    "ublock.crx",\n  ]\n  disable_compression = true\n}\n')
        crx = src / "fixture.crx"
        crx.write_bytes(b"Cr24\x03\x00\x00\x00fixture-only")
        entry = {"name": "dola-privacy.crx", "id": "a" * 32, "version": "0.2.0", "crx": str(crx)}
        return src, cc, gn, crx, entry

    def test_staging_installs_via_existing_provider_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, cc, gn, crx, entry = self.fixture(tmp)
            stager.stage(src, [entry])
            before = cc.read_bytes(), gn.read_bytes()
            stager.stage(src, [entry])
            self.assertEqual(before, (cc.read_bytes(), gn.read_bytes()))
            self.assertIn('"assets/dola-privacy.crx"', cc.read_text())
            self.assertIn('"assets/ublock.crx"', cc.read_text())
            self.assertIn('"dola-privacy.crx"', gn.read_text())
            self.assertEqual((gn.parent / entry["name"]).read_bytes(), crx.read_bytes())
            self.assertEqual(len(list((src / ".dola-backups").iterdir())), 1)
            crx.write_bytes(crx.read_bytes() + b"new-build")
            stager.stage(src, [entry])
            self.assertNotEqual(before[0], cc.read_bytes())

    def test_changed_anchor_and_invalid_input_leave_source_untouched(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, cc, gn, crx, entry = self.fixture(tmp)
            before = cc.read_bytes(), gn.read_bytes()
            with self.assertRaises(ValueError):
                stager.stage(src, [{**entry, "name": "../../escape.crx"}])
            self.assertEqual(before, (cc.read_bytes(), gn.read_bytes()))
            cc.write_text("different upstream format")
            with self.assertRaises(ValueError):
                stager.stage(src, [entry])
            self.assertEqual(cc.read_text(), "different upstream format")
            self.assertEqual(before[1], gn.read_bytes())


if __name__ == "__main__":
    unittest.main()
