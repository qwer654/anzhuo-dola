import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("preflight", ROOT / "tools/cobalt/preflight.py")
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


class Readiness(unittest.TestCase):
    def test_required_gn_and_comments(self):
        args = '\n'.join(f'{k} = {v} # comment' for k, v in preflight.REQUIRED_GN.items())
        self.assertEqual(preflight.gn_errors(args), [])
        self.assertIn("target_cpu", preflight.gn_errors(args.replace('"arm64"', '"x64"')))

    def test_duplicate_and_commented_settings_fail(self):
        args = '\n'.join(f'{k} = {v}' for k, v in preflight.REQUIRED_GN.items())
        self.assertIn("target_cpu", preflight.gn_errors(args + '\ntarget_cpu = "arm64"'))
        self.assertIn("enable_jni_multiplexing", preflight.gn_errors(args.replace('enable_jni_multiplexing', '# enable_jni_multiplexing')))

    def test_empty_environment_reports_every_missing_input(self):
        report = preflight.inspect({})
        self.assertFalse(report["ready"])
        failed = {c["name"] for c in report["checks"] if not c["ok"]}
        self.assertTrue({"COBALT_REPO", "COBALT_SRC", "DOLA30_CRX_KEY", "DOLA_PRIVACY_CRX_KEY"}.issubset(failed))

    def test_invalid_parallelism_rejected(self):
        for jobs in ("0", "-1", "65", "bad"):
            with self.subTest(jobs=jobs):
                check = next(c for c in preflight.inspect({"COBALT_JOBS": jobs})["checks"] if c["name"] == "jobs")
                self.assertFalse(check["ok"])


if __name__ == "__main__":
    unittest.main()
