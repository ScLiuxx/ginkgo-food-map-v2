"""验证私有配置生成边界；只用假凭据和临时文件，不访问服务器或云服务。"""

import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "prepare_application", Path(__file__).resolve().parents[1] / "application/prepare.py"
)
prepare = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prepare)


class ApplicationPreparationTest(unittest.TestCase):
    def setUp(self):
        self.database = (
            "DB_URL=jdbc:mysql://127.0.0.1:3306/foodmap\nDB_USERNAME=foodmap\n"
            "DB_PASSWORD=server-only-test-password\nDB_POOL_SIZE=10\n"
            "REDIS_HOST=127.0.0.1\nREDIS_PORT=6379\n"
            "REDIS_PASSWORD=redis-only-test-password\nREDIS_DATABASE=4\n"
        )
        self.settings = {
            "mail": {
                "host": "smtp.example.test", "port": 587,
                "username": "sender@example.test", "password": 'test$with"quote\\end',
                "properties": {"[mail.smtp.auth]": True, "[mail.smtp.starttls.required]": True},
            },
            "mailFrom": "sender@example.test",
            "cos": {"region": "ap-guangzhou", "bucket": "test-1234567890",
                    "secret-id": "test-id", "secret-key": "test-key"},
            "DB_PASSWORD": "must-not-replace-server-password",
        }

    def test_generated_configuration_reuses_server_database_and_smtp_tls(self):
        content = prepare.build_environment(self.database, self.settings)
        values = {}
        # 测试输入不含转义换行；JSON解码用于核对本用例的双引号及反斜杠保留。
        for line in content.splitlines():
            key, value = line.split("=", 1)
            values[key] = json.loads(value)
        self.assertEqual(values["DB_PASSWORD"], "server-only-test-password")
        self.assertNotIn("must-not-replace-server-password", content)
        self.assertEqual(values["SMTP_PASSWORD"], self.settings["mail"]["password"])
        properties = json.loads(values["SPRING_APPLICATION_JSON"])["spring"]["mail"]["properties"]
        self.assertTrue(properties["mail.smtp.starttls.required"])
        self.assertEqual(values["SERVER_ADDRESS"], "127.0.0.1")
        self.assertEqual(len(values["AUTH_CODE_HMAC_SECRET"]), 64)
        self.assertEqual(values["JWT_PRIVATE_KEY_LOCATION"], "file:/etc/foodmap/jwt-private.pem")

    def test_rejects_newline_injection_or_unresolved_local_placeholders(self):
        for invalid in ("test\nCOS_ENABLED=false", "test\rnew", "test\0new", "${SMTP_PASSWORD}"):
            self.settings["mail"]["password"] = invalid
            with self.subTest(value=repr(invalid)), self.assertRaises(ValueError):
                prepare.build_environment(self.database, self.settings)

    def test_rejects_duplicate_database_keys_and_temporary_cos_credentials(self):
        with self.assertRaises(ValueError):
            prepare.build_environment(self.database + "DB_PASSWORD=other\n", self.settings)
        self.settings["cos"]["session-token"] = "temporary-test-token"
        with self.assertRaises(ValueError):
            prepare.build_environment(self.database, self.settings)

    def test_existing_key_is_preserved_and_stops_initialization(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            original = directory / "jwt-private.pem"
            original.write_text("existing-test-key", encoding="utf-8")
            with self.assertRaises(ValueError):
                prepare.ensure_new_targets(directory)
            self.assertEqual(original.read_text(encoding="utf-8"), "existing-test-key")
            self.assertFalse((directory / "backend.env").exists())

    @unittest.skipIf(os.name == "nt", "Requires Linux symlink permission semantics")
    def test_dangling_symlink_is_not_treated_as_a_missing_key(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            target = directory / "jwt-private.pem"
            target.symlink_to(directory / "missing")
            with self.assertRaises(ValueError):
                prepare.ensure_new_targets(directory)
            self.assertTrue(target.is_symlink())
