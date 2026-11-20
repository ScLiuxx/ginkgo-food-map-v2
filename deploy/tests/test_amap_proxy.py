"""用Node执行njs的纯请求规则；假密钥和请求对象，不访问高德或业务服务。"""

import json
from pathlib import Path
import shutil
import subprocess
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "nginx/amap-proxy.js"


@unittest.skipUnless(shutil.which("node"), "Node is required for isolated njs policy checks")
class AmapProxyTest(unittest.TestCase):
    def run_requests(self, requests):
        runner = """
import fs from 'node:fs';
const source = fs.readFileSync(process.argv[1], 'utf8');
const policy = (await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'))).default;
const requests = JSON.parse(process.argv[2]);
const results = requests.map(input => {
    const request = {
        method: input.method || 'GET', uri: input.path || '/_AMapService/v3/place/text',
        variables: { args: input.args || '', foodmap_amap_key: 'test-server-key', foodmap_amap_jscode: 'test-server-code' },
        headersOut: {},
        return(status, body) { this.result = { status, body: JSON.parse(body) }; },
        internalRedirect(target) { this.result = { target, query: this.variables.foodmap_amap_query, path: this.variables.foodmap_amap_path }; }
    };
    policy.handle(request);
    if (input.response && request.result.target) {
        request.status = input.response.status;
        request.headersOut = { ...input.response.headers };
        policy.responseHeaders(request);
        request.result.headers = request.headersOut;
    }
    return request.result;
});
process.stdout.write(JSON.stringify(results));
"""
        result = subprocess.run(
            ["node", "--max-old-space-size=128", "--input-type=module", "-e", runner,
             str(SCRIPT), json.dumps(requests)], capture_output=True, text=True, timeout=15,
            encoding="utf-8", check=True,
        )
        return json.loads(result.stdout)

    def test_fixed_search_scope_and_server_credentials_override_client_values(self):
        from urllib.parse import parse_qs

        result = self.run_requests([{"args": "keywords=%E5%86%92%E8%8F%9C&key=client&KEY=other&jscode=client&types=010000&offset=50&city=other"}])[0]
        query = parse_qs(result["query"])
        self.assertEqual(result["target"], "@foodmap_amap_rest")
        self.assertEqual(query["keywords"], ["冒菜"])
        self.assertEqual(query["key"], ["test-server-key"])
        self.assertEqual(query["jscode"], ["test-server-code"])
        self.assertNotIn("KEY", query)
        self.assertEqual(query["types"], ["050000"])
        self.assertEqual(query["offset"], ["10"])
        self.assertEqual(query["city"], ["成都市"])
        self.assertEqual(query["citylimit"], ["false"])

    def test_forbidden_paths_methods_and_encoded_path_delimiters_never_redirect(self):
        requests = [
            {"path": "/_AMapService/v3/place/around"},
            {"path": "/_AMapService/v3/place/detail"},
            {"path": "/_AMapService/http://example.test"},
            {"path": "/_AMapService/v4/map/styles?key=client"},
            {"method": "POST", "args": "keywords=test"},
        ]
        for result in self.run_requests(requests):
            self.assertIn(result["status"], (404, 405))
            self.assertNotIn("target", result)
            self.assertNotIn("test-server-code", json.dumps(result))

    def test_invalid_search_and_malformed_query_fail_without_upstream(self):
        requests = [{"args": args} for args in (
            "keywords=", "keywords=%20%20", "keywords=" + "a" * 101,
            "keywords=test&page=0", "keywords=test&page=101", "keywords=%ZZ",
            "keywords=test%0Anew", "keywords=test&page=1%0A",
        )]
        for result in self.run_requests(requests):
            self.assertEqual(result["status"], 400)
            self.assertNotIn("target", result)

    def test_styles_and_coordinate_conversion_use_expected_fixed_locations(self):
        results = self.run_requests([
            {"path": "/_AMapService/v4/map/styles", "args": "style=normal"},
            {"path": "/_AMapService/v3/assistant/coordinate/convert", "args": "locations=103.93,30.75"},
        ])
        self.assertEqual(results[0]["target"], "@foodmap_amap_web")
        self.assertEqual(results[1]["target"], "@foodmap_amap_rest")

    def test_oversize_query_fails_and_boundary_page_is_accepted(self):
        results = self.run_requests([
            {"args": "keywords=test&padding=" + "a" * 8192},
            {"args": "keywords=test&page=100"},
        ])
        self.assertEqual(results[0]["status"], 404)
        self.assertEqual(results[1]["target"], "@foodmap_amap_rest")

    def test_jsonp_repairs_only_successful_text_response_types_and_keeps_nosniff(self):
        for path in ("/v3/place/text", "/v4/map/styles", "/v3/log/init"):
            for content_type in ("application/json;charset=UTF-8", "application/octet-stream", "text/plain"):
                with self.subTest(path=path, content_type=content_type):
                    result = self.run_requests([{
                        "path": "/_AMapService" + path,
                        "args": "keywords=test&callback=AMap._cb123",
                        "response": {"status": 200, "headers": {
                            "Content-Type": content_type, "X-Content-Type-Options": "nosniff",
                            "Cache-Control": "no-store",
                        }},
                    }])[0]
                    self.assertEqual(result["headers"]["Content-Type"], "application/javascript; charset=UTF-8")
                    self.assertEqual(result["headers"]["X-Content-Type-Options"], "nosniff")
                    self.assertEqual(result["headers"]["Cache-Control"], "no-store")

    def test_plain_json_images_and_errors_keep_original_types(self):
        for args, status, content_type in (
            ("keywords=test", 200, "application/json"),
            ("keywords=test&callback=cb", 200, "image/png"),
            ("keywords=test&callback=cb", 200, "text/html"),
            ("keywords=test&callback=cb", 400, "application/json"),
            ("keywords=test&callback=cb", 502, "application/octet-stream"),
        ):
            with self.subTest(args=args, status=status, content_type=content_type):
                result = self.run_requests([{"args": args, "response": {
                    "status": status, "headers": {"Content-Type": content_type},
                }}])[0]
                self.assertEqual(result["headers"]["Content-Type"], content_type)

    def test_invalid_jsonp_callbacks_never_reach_upstream(self):
        for callback in ("", "alert(1)", "cb;alert", "cb..name", "1cb", "cb%0A", "a" * 129,
                         "cb&callback=other", "cb&CALLBACK=other"):
            with self.subTest(callback=callback):
                result = self.run_requests([{"args": "keywords=test&callback=" + callback}])[0]
                self.assertEqual(result["status"], 400)
                self.assertNotIn("target", result)

    def test_both_production_upstreams_install_the_response_filter(self):
        directory = SCRIPT.parent
        common = (directory / "amap-proxy-common.conf").read_text(encoding="utf-8")
        site = (directory.parent / "nginx.conf").read_text(encoding="utf-8")
        self.assertIn("js_header_filter foodmap_amap.responseHeaders;", common)
        self.assertEqual(site.count("include /etc/nginx/foodmap/amap-proxy-common.conf;"), 2)
