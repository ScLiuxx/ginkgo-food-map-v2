/* Nginx njs入口。仅固定高德路径；不输出带安全密钥的URL或客户端坐标。 */
function fail(r, status, code) {
    r.headersOut['Content-Type'] = 'application/json';
    r.headersOut['Cache-Control'] = 'no-store';
    r.return(status, JSON.stringify({ code: code }));
}

function parseQuery(raw) {
    var pairs = [];
    raw.split('&').forEach(function (part) {
        if (!part) return;
        var separator = part.indexOf('=');
        var name = separator < 0 ? part : part.slice(0, separator);
        var value = separator < 0 ? '' : part.slice(separator + 1);
        name = decodeURIComponent(name.replace(/\+/g, ' '));
        value = decodeURIComponent(value.replace(/\+/g, ' '));
        if (/[\x00\r\n]/.test(name + value)) throw new Error('Invalid query');
        pairs.push([name, value]);
    });
    return pairs;
}

function first(pairs, name) {
    for (var index = 0; index < pairs.length; index++) {
        if (pairs[index][0] === name) return pairs[index][1];
    }
    return '';
}

function replace(pairs, name, value) {
    var result = pairs.filter(function (pair) {
        // 不让大小写变体或重复参数绕过服务端固定的值。
        return pair[0].toLowerCase() !== name.toLowerCase();
    });
    result.push([name, value]);
    return result;
}

function callbackName(pairs) {
    var callbacks = pairs.filter(function (pair) {
        return pair[0].toLowerCase() === 'callback';
    });
    if (!callbacks.length) return '';
    // JSONP只接受单一的标识符／点路径，拒绝空值、重复参数和脚本表达式。
    if (callbacks.length !== 1 || callbacks[0][0] !== 'callback'
            || callbacks[0][1].length > 128
            || !/^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*$/.test(callbacks[0][1])) {
        throw new Error('Invalid callback');
    }
    return callbacks[0][1];
}

function responseHeaders(r) {
    if (r.status < 200 || r.status >= 300) return;
    var type = r.headersOut['Content-Type'] || '';
    // 高德JSONP有时标成JSON或二进制；仅合法callback且成功的文本响应修正类型。
    // 普通JSON、图片、错误响应和nosniff保持原样，不修改上游正文。
    if (!/^(?:application\/(?:json|octet-stream)|text\/plain)(?:\s*;|$)/i.test(type)) return;
    try {
        if (callbackName(parseQuery(r.variables.foodmap_amap_query || ''))) {
            r.headersOut['Content-Type'] = 'application/javascript; charset=UTF-8';
        }
    } catch (error) {
        // 非正常转发上下文不放宽响应类型。
    }
}

function handle(r) {
    if (r.method !== 'GET') {
        r.headersOut.Allow = 'GET';
        fail(r, 405, 'MAP_METHOD_NOT_ALLOWED');
        return;
    }
    var key = r.variables.foodmap_amap_key || '';
    var code = r.variables.foodmap_amap_jscode || '';
    if (!key || !code) {
        fail(r, 503, 'MAP_PROXY_NOT_CONFIGURED');
        return;
    }
    var path = r.uri.slice('/_AMapService'.length);
    var raw = r.variables.args || '';
    if (!/^\/(?:v[34]\/(?:map(?:\/.*)?|log\/init)|v3\/assistant\/coordinate\/convert|v3\/place\/text)$/.test(path)
            || !/^\/[A-Za-z0-9/_.~-]+$/.test(path) || raw.length > 8192) {
        fail(r, 404, 'MAP_PATH_NOT_ALLOWED');
        return;
    }
    var pairs;
    try {
        pairs = parseQuery(raw);
        callbackName(pairs);
        if (path === '/v3/place/text') {
            var keyword = first(pairs, 'keywords').trim();
            var page = first(pairs, 'page') || '1';
            if (!keyword || keyword.length > 100 || !/^(?:[1-9]\d?|100)$/.test(page)) {
                fail(r, 400, 'MAP_SEARCH_INVALID_QUERY');
                return;
            }
            pairs = replace(pairs, 'keywords', keyword);
            pairs = replace(pairs, 'page', page);
            pairs = replace(pairs, 'types', '050000');
            pairs = replace(pairs, 'offset', '10');
            pairs = replace(pairs, 'extensions', 'base');
            pairs = replace(pairs, 'city', '成都市');
            pairs = replace(pairs, 'citylimit', 'false');
        }
        pairs = replace(pairs, 'key', key);
        pairs = replace(pairs, 'jscode', code);
        r.variables.foodmap_amap_query = pairs.map(function (pair) {
            return encodeURIComponent(pair[0]) + '=' + encodeURIComponent(pair[1]);
        }).join('&');
    } catch (error) {
        fail(r, 400, 'MAP_SEARCH_INVALID_QUERY');
        return;
    }
    r.variables.foodmap_amap_path = path;
    // 命名location固定上游、TLS和日志策略；不是由请求指定的任意代理地址。
    r.internalRedirect(path.indexOf('/v4/map/styles') === 0
        ? '@foodmap_amap_web' : '@foodmap_amap_rest');
}

export default { handle: handle, responseHeaders: responseHeaders };
