"""用临时目录和模拟systemctl验证真实发布脚本，不操作任何系统服务。"""

import hashlib
import io
import os
import pathlib
import shutil
import subprocess
import tarfile
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'deploy.sh'


@unittest.skipIf(os.name == 'nt', 'Deployment script requires Linux filesystem semantics')
class DeployTest(unittest.TestCase):
    """验证首次上线、坏版本回滚和校验失败时不触碰当前版本。"""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        (self.root / 'incoming').mkdir()
        (self.root / 'releases').mkdir()
        (self.root / 'bin').mkdir()
        sudo = self.root / 'bin/sudo'
        sudo.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$FOODMAP_DEPLOY_ROOT/service-calls"\n')
        sudo.chmod(0o755)
        self.environment = os.environ.copy()
        self.environment['FOODMAP_DEPLOY_ROOT'] = str(self.root)
        self.environment['PATH'] = str(self.root / 'bin') + ':' + self.environment['PATH']

    def tearDown(self):
        self.temporary.cleanup()

    def prepare(self, commit, fail=False):
        release_id = commit + '-123-1'
        incoming = self.root / 'incoming' / release_id
        incoming.mkdir()
        with tarfile.open(incoming / 'release.tar.gz', 'w:gz') as archive:
            for name, content in {
                'backend.jar': b'test artifact',
                'commit.txt': commit.encode(),
                'frontend/index.html': b'<div id="app"></div>',
                'frontend/version.txt': commit.encode(),
            }.items():
                entry = tarfile.TarInfo(name)
                entry.size = len(content)
                entry.mode = 0o644
                archive.addfile(entry, io.BytesIO(content))
        digest = hashlib.sha256((incoming / 'release.tar.gz').read_bytes()).hexdigest()
        (incoming / 'SHA256SUMS').write_text(digest + '  release.tar.gz\n')
        # 新版本失败，但回滚检查上一版时应成功。
        (incoming / 'healthcheck.py').write_text(
            'import sys\nraise SystemExit(1 if ' + repr(fail) + ' and '
            + repr(commit) + ' in sys.argv else 0)\n')
        return release_id

    def deploy(self, release_id):
        return subprocess.run(['bash', str(SCRIPT), release_id], env=self.environment,
                              text=True, capture_output=True, timeout=10)

    def test_first_release_can_be_activated(self):
        release_id = self.prepare('a' * 40)
        result = self.deploy(release_id)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / 'current').resolve(), self.root / 'releases' / release_id)

    def test_failed_release_restores_previous_application(self):
        first = self.prepare('a' * 40)
        self.assertEqual(self.deploy(first).returncode, 0)
        second = self.prepare('b' * 40, fail=True)
        result = self.deploy(second)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.root / 'current').resolve(), self.root / 'releases' / first)
        self.assertIn('Previous application release restored', result.stderr)

    def test_failed_first_release_does_not_leave_active_link(self):
        release_id = self.prepare('a' * 40, fail=True)
        self.assertNotEqual(self.deploy(release_id).returncode, 0)
        self.assertFalse((self.root / 'current').is_symlink())
        self.assertIn('stop foodmap-backend.service', (self.root / 'service-calls').read_text())

    def test_checksum_failure_does_not_restart_services(self):
        release_id = self.prepare('a' * 40)
        (self.root / 'incoming' / release_id / 'release.tar.gz').write_bytes(b'corrupt')
        self.assertNotEqual(self.deploy(release_id).returncode, 0)
        self.assertFalse((self.root / 'service-calls').exists())

    def test_rejects_directory_traversal_release_id(self):
        self.assertNotEqual(self.deploy('../outside').returncode, 0)
        self.assertFalse((self.root / 'service-calls').exists())

    def test_rejects_archive_symlink_before_switching(self):
        release_id = self.prepare('a' * 40)
        incoming = self.root / 'incoming' / release_id
        with tarfile.open(incoming / 'release.tar.gz', 'w:gz') as archive:
            entry = tarfile.TarInfo('frontend/escape')
            entry.type = tarfile.SYMTYPE
            entry.linkname = '/etc'
            archive.addfile(entry)
        digest = hashlib.sha256((incoming / 'release.tar.gz').read_bytes()).hexdigest()
        (incoming / 'SHA256SUMS').write_text(digest + '  release.tar.gz\n')
        self.assertNotEqual(self.deploy(release_id).returncode, 0)
        self.assertFalse((self.root / 'service-calls').exists())


if __name__ == '__main__':
    unittest.main()
