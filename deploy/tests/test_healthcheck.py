"""通过本机临时HTTP服务验证检查逻辑，不连接生产服务器或发送邮件。"""

import importlib.util
import json
import pathlib
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODULE = pathlib.Path(__file__).resolve().parents[1] / 'healthcheck.py'
SPEC = importlib.util.spec_from_file_location('healthcheck', MODULE)
healthcheck = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(healthcheck)


class HealthcheckTest(unittest.TestCase):
    """检查版本、代理或依赖失败时，发布不能被误判为成功。"""

    def setUp(self):
        self.responses = {
            '/actuator/health': (200, {'status': 'UP'}),
            '/version.txt': (200, 'a' * 40),
            '/login': (200, '<div id="app"></div>'),
            '/api/public/status': (200, {'data': {'application': 'foodmap'}}),
            '/auth/me': (401, {'code': 'AUTH_UNAUTHENTICATED'}),
        }
        responses = self.responses

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                status, body = responses.get(self.path, (404, {}))
                self.send_response(status)
                self.end_headers()
                data = json.dumps(body) if isinstance(body, dict) else body
                self.wfile.write(data.encode())

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = 'http://127.0.0.1:' + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def check(self):
        return healthcheck.check(self.url, self.url, 'a' * 40, 1)

    def test_accepts_healthy_application_and_expected_unauthorized_response(self):
        self.assertIsNone(self.check())

    def test_rejects_unhealthy_dependencies(self):
        self.responses['/actuator/health'] = (503, {'status': 'DOWN'})
        self.assertEqual(self.check(), 'backend health')

    def test_rejects_wrong_frontend_version(self):
        self.responses['/version.txt'] = (200, 'b' * 40)
        self.assertEqual(self.check(), 'frontend version')

    def test_rejects_spa_fallback_in_place_of_proxy(self):
        self.responses['/api/public/status'] = (200, '<div id="app"></div>')
        self.assertEqual(self.check(), 'API proxy')

    def test_rejects_public_identity_endpoint(self):
        self.responses['/auth/me'] = (200, {'code': 'OK'})
        self.assertEqual(self.check(), 'authentication proxy')


if __name__ == '__main__':
    unittest.main()
