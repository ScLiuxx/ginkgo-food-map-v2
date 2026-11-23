#!/usr/bin/env python3
"""Install a reviewed HTTPS site and certificate, preserving the current AMap proxy."""

import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile


SITE = Path('/etc/nginx/sites-available/foodmap')
ENABLED = Path('/etc/nginx/sites-enabled/foodmap')
TLS = Path('/etc/nginx/foodmap/tls/nidianfoodmap.cn')
DOMAINS = ('nidianfoodmap.cn', 'www.nidianfoodmap.cn')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def regular(path, private=False):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or (private and info.st_mode & 0o077):
        raise RuntimeError('Expected regular input files; private key must have mode 600.')
    return path.read_bytes()


def atomic_write(path, content, mode):
    fd, temporary = tempfile.mkstemp(prefix='.https-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            os.fchmod(stream.fileno(), mode)
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def command(args):
    result = subprocess.run(args, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError('Certificate verification failed; no private material printed.')
    return result.stdout


def main():
    if os.name != 'posix' or os.geteuid() != 0:
        raise RuntimeError('Run this installer using sudo python3.')
    os.umask(0o077)
    source = Path(__file__).resolve().parent
    manifest = json.loads(regular(source / 'manifest.json'))
    candidate = regular(source / 'https-site.conf')
    assert digest(candidate) == manifest['siteAfterSha256']
    if not ENABLED.is_symlink() or ENABLED.resolve() != SITE:
        raise RuntimeError('Unexpected enabled site; existing configuration preserved.')
    before = regular(SITE)
    if digest(before) == manifest['siteAfterSha256']:
        assert digest(regular(TLS / 'fullchain.pem')) == manifest['bundleSha256']
        command(['nginx', '-t'])
        print('Reviewed HTTPS configuration already installed; no changes made.')
        return
    if digest(before) != manifest['siteBeforeSha256']:
        raise RuntimeError('Site differs from the reviewed baseline; existing configuration preserved.')
    for directory in (TLS.parent.parent, TLS.parent, TLS):
        if directory.is_symlink():
            raise RuntimeError('Unexpected TLS directory symlink; no update performed.')
    if TLS.exists():
        raise RuntimeError('Certificate destination already exists; preserved it.')
    certificate = regular(source / 'fullchain.pem')
    private_key = regular(source / 'privkey.pem', private=True)
    assert digest(certificate) == manifest['bundleSha256']
    cert_path, key_path = str(source / 'fullchain.pem'), str(source / 'privkey.pem')
    command(['openssl', 'x509', '-in', cert_path, '-checkend', '86400', '-noout'])
    for domain in DOMAINS:
        command(['openssl', 'x509', '-in', cert_path, '-checkhost', domain, '-noout'])
    assert command(['openssl', 'x509', '-in', cert_path, '-pubkey', '-noout']) == command(
        ['openssl', 'pkey', '-in', key_path, '-pubout'])
    command(['openssl', 'verify', '-CAfile', '/etc/ssl/certs/ca-certificates.crt',
             '-untrusted', cert_path, cert_path])
    command(['nginx', '-t'])
    backup = Path(tempfile.mkdtemp(prefix='.foodmap-before-https-', dir='/etc/nginx'))
    backup.chmod(0o700)
    atomic_write(backup / 'site.conf', before, 0o600)
    atomic_write(backup / 'manifest.json', regular(source / 'manifest.json'), 0o600)

    def checked(args):
        result = subprocess.run(args, capture_output=True, timeout=30)
        with (backup / 'validation.log').open('ab') as stream:
            stream.write(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError('Nginx validation or reload failed; root-only diagnostics saved.')

    try:
        TLS.mkdir(parents=True, mode=0o700)
        atomic_write(TLS / 'fullchain.pem', certificate, 0o644)
        atomic_write(TLS / 'privkey.pem', private_key, 0o600)
        atomic_write(SITE, candidate, stat.S_IMODE(SITE.stat().st_mode))
        checked(['nginx', '-t'])
        checked(['systemctl', 'reload', 'nginx'])
        checked(['curl', '--fail', '--silent', '--show-error', '--max-time', '10',
                 '--retry', '4', '--retry-connrefused', '--retry-delay', '1', '--retry-max-time', '20', '--noproxy', '*',
                 '--resolve', 'nidianfoodmap.cn:443:127.0.0.1', 'https://nidianfoodmap.cn/version.txt'])
    except Exception:
        atomic_write(SITE, before, 0o644)
        try:
            checked(['nginx', '-t'])
            checked(['systemctl', 'reload', 'nginx'])
        except Exception:
            raise RuntimeError('HTTPS setup failed and rollback needs operator attention; backup: ' + str(backup)) from None
        if TLS.exists():
            for name in ('fullchain.pem', 'privkey.pem'):
                (TLS / name).unlink(missing_ok=True)
            TLS.rmdir()
        raise RuntimeError('HTTPS setup failed; previous site restored. Backup: ' + str(backup)) from None
    # Leave the only server copy of the private key in the root-only TLS directory.
    (source / 'privkey.pem').unlink()
    print('HTTPS enabled for nidianfoodmap.cn and www.nidianfoodmap.cn; Nginx reloaded.')
    print('HTTP domain requests redirect to HTTPS; IP and loopback HTTP remain available.')
    print('Backend, database and AMap credentials unchanged. Backup: ' + str(backup))
    print('Certificate expires: ' + manifest['validUntil'] + '; renew before expiry.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        if isinstance(error, RuntimeError):
            raise SystemExit(str(error)) from None
        raise SystemExit('HTTPS installation stopped; inspect root-only diagnostics. No credentials printed.') from None
