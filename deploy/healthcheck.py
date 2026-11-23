"""核对后端依赖、Nginx、认证代理和实际前端版本，失败仅输出检查名称，不输出凭证或响应正文。"""

import argparse
import json
import time
import urllib.error
import urllib.request


def request(url, timeout):
    """读取HTTP错误响应以校验预期401；网络故障交给重试循环处理。"""
    try:
        response = urllib.request.urlopen(url, timeout=timeout)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.code, response.read()


def check(backend, frontend, commit, timeout):
    """必须同时确认应用健康、前端提交号和接口代理，不能仅凭TCP端口判断发布成功。"""
    checks = [
        ('backend health', backend + '/actuator/health', 200, lambda body: json.loads(body).get('status') == 'UP'),
        ('frontend version', frontend + '/version.txt', 200, lambda body: body.decode().strip() == commit),
        ('frontend page', frontend + '/login', 200, lambda body: b'<div id="app"></div>' in body),
        ('API proxy', frontend + '/api/public/status', 200,
         lambda body: json.loads(body).get('data', {}).get('application') == 'foodmap'),
        ('authentication proxy', frontend + '/auth/me', 401,
         lambda body: json.loads(body).get('code') == 'AUTH_UNAUTHENTICATED'),
    ]
    for name, url, expected, validate in checks:
        try:
            status, body = request(url, timeout)
            if status != expected or not validate(body):
                return name
        except (OSError, ValueError, TypeError):
            return name
    return None


def main():
    """健康检查使用有限次数和单请求超时，避免坏版本无限占用流水线。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--backend', default='http://127.0.0.1:8080')
    parser.add_argument('--frontend', default='http://127.0.0.1')
    parser.add_argument('--commit', required=True)
    parser.add_argument('--attempts', type=int, default=12)
    parser.add_argument('--delay', type=float, default=3)
    parser.add_argument('--timeout', type=float, default=15)
    args = parser.parse_args()
    for attempt in range(args.attempts):
        failure = check(args.backend, args.frontend, args.commit, args.timeout)
        if failure is None:
            print('Application and proxy health checks passed')
            return 0
        print(f'Waiting for {failure}; attempt {attempt + 1}/{args.attempts}', flush=True)
        if attempt + 1 < args.attempts:
            time.sleep(args.delay)
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
