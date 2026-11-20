"""临时目录验证代理更新与失败恢复；不读系统配置或访问业务服务。"""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location(
    "update_jsonp", Path(__file__).resolve().parents[1] / "nginx/update-jsonp.py"
)
updater = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(updater)


class JsonpUpdateTest(unittest.TestCase):
    def fixture(self, directory):
        source, destination = directory / "source", directory / "installed"
        source.mkdir()
        destination.mkdir()
        baseline = {}
        for name in updater.FILES:
            (source / name).write_bytes(b"new public proxy\n")
            (destination / name).write_bytes(b"old public proxy\r\n")
            baseline[name] = hashlib.sha256(b"old public proxy\n").hexdigest()
        (destination / "credentials.conf").write_bytes(b"private-test-value")
        return source, destination, baseline

    def test_update_backs_up_both_files_preserves_secrets_and_is_repeatable(self):
        with tempfile.TemporaryDirectory() as temporary:
            source, destination, baseline = self.fixture(Path(temporary))
            validate, reload = Mock(), Mock()
            with patch.object(updater, "FILES", baseline):
                backup = updater.update(source, destination, validate, reload)
                self.assertEqual(validate.call_count, 2)
                reload.assert_called_once()
                for name in baseline:
                    self.assertEqual((backup / name).read_bytes(), b"old public proxy\r\n")
                    self.assertEqual((destination / name).read_bytes(), b"new public proxy\n")
                self.assertIsNone(updater.update(source, destination, validate, reload))
                self.assertEqual(reload.call_count, 1)
                self.assertEqual((destination / "credentials.conf").read_bytes(), b"private-test-value")

    def test_unknown_second_file_prevents_any_writes(self):
        with tempfile.TemporaryDirectory() as temporary:
            source, destination, baseline = self.fixture(Path(temporary))
            (destination / "amap-proxy-common.conf").write_bytes(b"manually changed")
            before = {p.name: p.read_bytes() for p in destination.iterdir()}
            validate, reload = Mock(), Mock()
            with patch.object(updater, "FILES", baseline):
                with self.assertRaisesRegex(RuntimeError, "differs from the reviewed baseline"):
                    updater.update(source, destination, validate, reload)
            self.assertEqual(before, {p.name: p.read_bytes() for p in destination.iterdir()})
            validate.assert_not_called()
            reload.assert_not_called()

    def test_invalid_existing_nginx_never_writes(self):
        with tempfile.TemporaryDirectory() as temporary:
            source, destination, baseline = self.fixture(Path(temporary))
            reload = Mock()
            with patch.object(updater, "FILES", baseline), self.assertRaises(RuntimeError):
                updater.update(source, destination, Mock(side_effect=RuntimeError("invalid")), reload)
            self.assertEqual((destination / "amap-proxy.js").read_bytes(), b"old public proxy\r\n")
            reload.assert_not_called()

    def test_validation_and_reload_failures_restore_original_files_and_running_config(self):
        for stage in ("validate", "reload"):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as temporary:
                source, destination, baseline = self.fixture(Path(temporary))
                validate = Mock(side_effect=[None, RuntimeError("invalid"), None]) if stage == "validate" else Mock()
                reload = Mock(side_effect=[RuntimeError("failed"), None]) if stage == "reload" else Mock()
                with patch.object(updater, "FILES", baseline), self.assertRaisesRegex(RuntimeError, "running configuration restored"):
                    updater.update(source, destination, validate, reload)
                for name in baseline:
                    self.assertEqual((destination / name).read_bytes(), b"old public proxy\r\n")
                self.assertEqual(reload.call_count, 2 if stage == "reload" else 1)

    def test_failed_recovery_reports_manual_inspection(self):
        with tempfile.TemporaryDirectory() as temporary:
            source, destination, baseline = self.fixture(Path(temporary))
            with patch.object(updater, "FILES", baseline), self.assertRaisesRegex(RuntimeError, "manual inspection"):
                updater.update(source, destination, Mock(), Mock(side_effect=RuntimeError("failed")))
            self.assertEqual((destination / "amap-proxy.js").read_bytes(), b"old public proxy\r\n")
