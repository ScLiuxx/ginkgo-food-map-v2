"""验证模块缺失时的公开诊断和凭据拒绝，不访问系统Nginx配置。"""

import importlib.util
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "prepare_nginx", Path(__file__).resolve().parents[1] / "nginx/prepare.py"
)
prepare = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prepare)


class NginxPreparationTest(unittest.TestCase):
    def test_missing_njs_reports_actionable_reason_without_writing_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            unrelated = directory / "other.conf"
            unrelated.write_text("load_module modules/ngx_stream_module.so;\n", encoding="utf-8")
            before = {path.name: path.read_bytes() for path in directory.iterdir()}
            with self.assertRaisesRegex(prepare.SetupError, "njs module is not enabled"):
                prepare.require_njs_module(directory)
            self.assertEqual(before, {path.name: path.read_bytes() for path in directory.iterdir()})

    def test_njs_detection_accepts_distribution_specific_configuration_name(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "90-official-njs.conf").write_text(
                "load_module modules/ngx_http_js_module.so;\n", encoding="utf-8"
            )
            prepare.require_njs_module(directory)

    def test_invalid_credentials_are_not_echoed_in_public_error(self):
        secret = "private-test-value-with-newline\n"
        with self.assertRaises(prepare.SetupError) as caught:
            prepare.credentials({"key": "a" * 32, "jscode": secret})
        self.assertNotIn(secret, str(caught.exception))
