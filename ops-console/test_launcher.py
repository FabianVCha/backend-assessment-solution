import importlib.util
from importlib.machinery import SourceFileLoader
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch


loader = SourceFileLoader("meridian_launcher", str(Path(__file__).resolve().parents[1] / "meridian"))
launcher = importlib.util.module_from_spec(importlib.util.spec_from_loader(loader.name, loader))
loader.exec_module(launcher)


class LauncherTest(unittest.TestCase):
    def test_profile_is_read_only_and_origin_is_derived_from_config(self):
        with patch.object(launcher, "local_profile", return_value={
                "INTAKE_LOCAL_PORT": "18080", "INTAKE_OPS_HS256_KEY_BASE64": "unused",
        }), patch.object(launcher, "jwt", return_value="stub-token") as issue, \
                patch.object(launcher, "console_main", return_value=0) as console, patch.dict(os.environ, {}, clear=True):
            self.assertEqual(0, launcher.run([]))
            issue.assert_called_once_with(["CL", "CO", "MX"], False)
            console.assert_called_once_with(["--base-url", "http://127.0.0.1:18080"])

    def test_explicit_origin_does_not_load_identity_profile(self):
        with patch.object(launcher, "local_profile") as profile, \
                patch.object(launcher, "console_main", return_value=0) as console:
            self.assertEqual(0, launcher.run(["--base-url=http://127.0.0.1:8181"]))
            profile.assert_not_called()
            console.assert_called_once_with(["--base-url=http://127.0.0.1:8181"])

    def test_profile_parser_does_not_execute_shell_and_requires_private_mode(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / ".meridian.local.env"
            path.write_text("INTAKE_LOCAL_PORT=8080\nUNRELATED=$(touch unexpected)\n")
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                launcher.local_profile(path)
            path.chmod(0o600)
            self.assertEqual({"INTAKE_LOCAL_PORT": "8080"}, launcher.local_profile(path))
