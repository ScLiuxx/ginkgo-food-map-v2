"""模拟SSH／SCP，验证上传失败不激活、主机固定和临时凭据清理，不连接服务器。"""

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "ssh-publish.sh"
BASH = (str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe")
        if os.name == "nt" else shutil.which("bash"))


@unittest.skipUnless(BASH and Path(BASH).is_file(), "Bash is required for SSH transport checks")
class SshPublishTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        (self.root / "deploy").mkdir()
        (self.root / "deploy/deploy.sh").write_text("# fake deployment\n")
        (self.root / "deploy/healthcheck.py").write_text("# fake health check\n")
        artifact = b"checked artifact"
        (self.root / "release.tar.gz").write_bytes(artifact)
        (self.root / "SHA256SUMS").write_bytes(
            (hashlib.sha256(artifact).hexdigest() + "  release.tar.gz\n").encode())
        (self.root / "mocks.sh").write_text(r'''
ssh-keygen() {
    printf 'host-check %s\n' "$*" >> calls
    test "${FAIL_HOST:-0}" != 1
}
ssh() {
    printf 'ssh %s\n' "$*" >> calls
    case "${*: -1}" in
        *"bash './deploy.sh'"*) test "${FAIL_ACTIVATE:-0}" != 1 ;;
        *) test "${FAIL_CONNECT:-0}" != 1 ;;
    esac
}
scp() {
    printf 'scp %s\n' "$*" >> calls
    if [[ "$*" == *release.part.0001* && "${FAIL_SECOND_CHUNK:-0}" == 1 ]]; then
        return 1
    fi
    if [[ "$*" == *healthcheck.py* && "${FAIL_METADATA:-0}" == 1 ]]; then
        return 1
    fi
    test "${FAIL_UPLOAD:-0}" != 1
}
export -f ssh-keygen ssh scp
''', encoding="utf-8", newline="\n")
        self.environment = os.environ.copy()
        self.environment.update({
            "DEPLOY_HOST": "43.139.87.34", "DEPLOY_PORT": "22",
            "DEPLOY_USER": "foodmap-deploy", "DEPLOY_SSH_KEY": "fake-private-key",
            "DEPLOY_KNOWN_HOSTS": "fake-pinned-host-key",
            "GITHUB_SHA": "a" * 40, "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2",
            "SSH_PUBLISH_SCRIPT": SCRIPT.as_posix(), "TMPDIR": self.root.as_posix(),
        })

    def tearDown(self):
        self.temporary.cleanup()

    def publish(self, **environment):
        result = subprocess.run(
            [BASH, "-c", 'source ./mocks.sh; bash "$SSH_PUBLISH_SCRIPT"'],
            cwd=self.root, env={**self.environment, **environment},
            text=True, capture_output=True, timeout=15)
        self.assertEqual(list(self.root.glob("tmp.*")), [], "Temporary credentials must be removed")
        self.assertNotIn("fake-private-key", result.stdout + result.stderr)
        return result

    def calls(self):
        path = self.root / "calls"
        return path.read_text() if path.exists() else ""

    def test_success_uploads_before_activation_and_pins_host(self):
        result = self.publish()
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.calls().splitlines()
        self.assertEqual(len(calls), 5)
        self.assertIn("StrictHostKeyChecking=yes", calls[1])
        self.assertIn("BatchMode=yes", calls[1])
        self.assertIn("mkdir '/opt/foodmap/incoming/" + "a" * 40 + "-123-2'", calls[1])
        self.assertTrue(calls[2].startswith("scp "))
        self.assertIn("release.part.0000", calls[2])
        self.assertIn("healthcheck.py", calls[3])
        self.assertIn("cat release.part.* > release.tar.gz", calls[4])
        self.assertIn("sha256sum --check --status SHA256SUMS", calls[4])
        self.assertIn("bash './deploy.sh'", calls[4])

    def test_failed_upload_does_not_activate_and_cleans_credentials(self):
        self.assertNotEqual(self.publish(FAIL_UPLOAD="1").returncode, 0)
        self.assertIn("scp ", self.calls())
        self.assertNotIn("bash './deploy.sh'", self.calls())

    def test_failure_in_one_of_multiple_chunks_never_uploads_metadata_or_activates(self):
        artifact = b"x" * (5 * 1024 * 1024)
        (self.root / "release.tar.gz").write_bytes(artifact)
        (self.root / "SHA256SUMS").write_bytes(
            (hashlib.sha256(artifact).hexdigest() + "  release.tar.gz\n").encode())
        self.assertNotEqual(self.publish(FAIL_SECOND_CHUNK="1").returncode, 0)
        self.assertIn("release.part.0000", self.calls())
        self.assertIn("release.part.0001", self.calls())
        self.assertNotIn("healthcheck.py", self.calls())
        self.assertNotIn("bash './deploy.sh'", self.calls())

    def test_failed_metadata_upload_does_not_assemble_or_activate(self):
        self.assertNotEqual(self.publish(FAIL_METADATA="1").returncode, 0)
        self.assertIn("healthcheck.py", self.calls())
        self.assertNotIn("cat release.part.*", self.calls())

    def test_failed_connection_does_not_upload(self):
        self.assertNotEqual(self.publish(FAIL_CONNECT="1").returncode, 0)
        self.assertIn("ssh ", self.calls())
        self.assertNotIn("scp ", self.calls())

    def test_missing_pinned_address_never_connects(self):
        self.assertNotEqual(self.publish(FAIL_HOST="1").returncode, 0)
        self.assertIn("host-check ", self.calls())
        self.assertNotIn("ssh ", self.calls())

    def test_remote_activation_failure_is_reported(self):
        self.assertNotEqual(self.publish(FAIL_ACTIVATE="1").returncode, 0)
        self.assertIn("bash './deploy.sh'", self.calls())

    def test_nondefault_port_uses_bracketed_known_host(self):
        result = self.publish(DEPLOY_PORT="2222")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("-F [43.139.87.34]:2222", self.calls())
        self.assertIn("-P 2222", self.calls())

    def test_rejects_injected_target_before_any_connection(self):
        for changes in ({"DEPLOY_HOST": "host;touch unexpected"},
                        {"DEPLOY_PORT": "65536"}, {"DEPLOY_USER": "root"},
                        {"GITHUB_RUN_ID": "../outside"}, {"DEPLOY_SSH_KEY": ""}):
            with self.subTest(changes=changes):
                self.assertNotEqual(self.publish(**changes).returncode, 0)
                self.assertEqual(self.calls(), "")

    def test_corrupt_artifact_never_connects(self):
        (self.root / "release.tar.gz").write_bytes(b"corrupt")
        self.assertNotEqual(self.publish().returncode, 0)
        self.assertEqual(self.calls(), "")
