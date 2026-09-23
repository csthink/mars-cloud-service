"""Exercise packaged resource servers without printing credentials or raw failure bodies."""
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request

# 健康探针在管理端口上，业务断言仍走业务端口，所以两组地址都要传进来。
sample, upms, sample_management, upms_management, directory, phase = sys.argv[1:]
tokens = pathlib.Path(directory)
passed = 0


def request(base, path, token=None, payload=None, headers=None):
    actual = dict(headers or {})
    if token:
        actual['Authorization'] = 'Bearer ' + (tokens / (token + '.token')).read_text()
    data = None if payload is None else json.dumps(payload).encode()
    if data is not None:
        actual['Content-Type'] = 'application/json'
    req = urllib.request.Request(base + path, data=data, headers=actual)
    try:
        response = urllib.request.urlopen(req, timeout=12)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = response.read().decode()
        return response.status, json.loads(body), dict(response.headers), body


def check(label, condition):
    global passed
    if not condition:
        raise SystemExit('FAIL ' + label)
    passed += 1
    print('ok   ' + label)


if phase == 'unavailable':
    status, body, _, _ = request(sample, '/v1/security/decision', 'admin')
    check('PDP shutdown denies method with 503 / 62004', status == 503 and body.get('code') == '62004')
else:
    status, body, _, _ = request(sample_management, '/actuator/health')
    check('anonymous sample health probe on the management port', status == 200 and body.get('status') == 'UP')
    status, body, _, _ = request(upms_management, '/actuator/health')
    check('anonymous UPMS health probe on the management port', status == 200 and body.get('status') == 'UP')
    # 业务端口的地址带 context path；安全组件对健康探针路径放行，没有管理端点时请求落到 404。
    status, _, _, _ = request(sample, '/actuator/health')
    check('sample business port exposes no actuator', status == 404)
    status, body, headers, _ = request(sample, '/v1/security/me')
    check('missing token returns 401 / 62001', status == 401 and body.get('code') == '62001')
    check('missing token includes Bearer challenge', any(k.lower() == 'www-authenticate' and v == 'Bearer' for k, v in headers.items()))
    status, body, _, _ = request(sample, '/v1/security/me', headers={'Authorization': 'Bearer sentinel-invalid-token'})
    check('invalid token returns 401 / 62002', status == 401 and body.get('code') == '62002')
    status, body, _, _ = request(sample, '/v1/security/me', 'expired')
    check('expired token returns 401 / 62002', status == 401 and body.get('code') == '62002')
    status, body, _, _ = request(sample, '/v1/security/me?access_token=sentinel-query-token', headers={'X-Mars-Subject': 'local-admin'})
    check('query and internal header cannot authenticate', status == 401 and body.get('code') == '62001')
    status, body, _, _ = request(sample, '/v1/security/me', 'admin', headers={'X-Mars-Subject': 'forged', 'X-Mars-Tenant-Id': 'forged'})
    check('verified identity overrides spoofed internal headers', status == 200 and body.get('result') == {'subject': 'local-admin', 'clientId': 'test-client', 'tenantId': 'default'})
    payload = {'caller_id': 'different-user', 'action': 'view', 'resource': 'demo:view:domain:kubernetes-ops'}
    status, body, _, _ = request(upms, '/v1/decision', 'admin', payload)
    check('UPMS refuses another subject', status == 403 and body.get('code') == '62006')
    deadline = time.monotonic() + 90
    while True:
        status, body, _, _ = request(sample, '/v1/security/decision', 'admin')
        if status == 200 or time.monotonic() > deadline:
            break
        time.sleep(1)
    check('multi-audience token permits real sample to UPMS method check', status == 200 and body.get('result', {}).get('allowed') is True)
    status, body, _, _ = request(sample, '/v1/security/decision', 'deny')
    check('PDP deny prevents protected method', status == 403 and body.get('code') == '62003')
    status, body, _, _ = request(sample, '/v1/security/decision', 'sample-only')
    check('missing UPMS audience stops protected method', status == 502 and body.get('code') == '62005')
    sentinel_headers = {'Proxy-Authorization': 'sentinel-proxy-secret', 'Cookie': 'session=sentinel-cookie-secret',
                        'Set-Cookie': 'sentinel-set-cookie-secret', 'X-Api-Key': 'sentinel-api-secret', 'X-Debug-Marker': 'visible-marker'}
    status, body, _, text = request(sample, '/v1/orders/missing', 'admin', headers=sentinel_headers)
    check('application error keeps its status', status == 404)
    check('credential headers absent from application error', all(secret not in text for secret in sentinel_headers.values() if secret != 'visible-marker'))
    if phase == 'production':
        check('production error has no development details', 'result' not in body)
    else:
        check('development error retains nonsensitive header', 'visible-marker' in text)
    check('access token absent from response', (tokens / 'admin.token').read_text() not in text)
print(f'{phase}: {passed} security assertions passed')
