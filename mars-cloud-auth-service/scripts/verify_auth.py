#!/usr/bin/env python3
"""Exercise the real HTTPS authorization service without logging credentials or tokens."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import html
import copy
from http.cookiejar import MozillaCookieJar
import json
import os
from pathlib import Path
import re
import secrets
import ssl
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, target):
        return None


class Browser:
    def __init__(self, base, ca, cookies=None):
        self.base = base
        self.cookies = cookies if cookies is not None else MozillaCookieJar()
        self.opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPCookieProcessor(self.cookies),
            urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(ca))))

    def request(self, path, data=None, headers=None):
        url = path if path.startswith('https://') else self.base + path
        payload = urllib.parse.urlencode(data).encode() if data is not None else None
        request = urllib.request.Request(url, data=payload, headers=headers or {})
        try:
            result = self.opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            result = error
        return result.status, result.headers, result.read()


def require(value, description):
    if not value:
        raise AssertionError(description)


def query(parameters):
    return '/oauth2/authorize?' + urllib.parse.urlencode(parameters)


def new_request(client, callback, **overrides):
    verifier = secrets.token_urlsafe(48)
    values = dict(response_type='code', client_id=client, redirect_uri=callback, scope='openid',
                  state=secrets.token_urlsafe(24), code_challenge_method='S256',
                  code_challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('='))
    values.update(overrides)
    return verifier, values


def hidden_fields(body):
    return {name: html.unescape(value) for name, value in re.findall(r'name="([^"]+)"[^>]*value="([^"]*)"', body.decode())}


def authorization(browser, client, callback, consent=True, **overrides):
    verifier, values = new_request(client, callback, **overrides)
    status, headers, body = browser.request(query(values))
    require(status == 302, 'Authorization must redirect (status=' + str(status) + ')' )
    location = headers.get('Location', '')
    if '/login/consent?' in location:
        require(client in {'test-native', 'csthink-assistant'}, 'Only native clients require consent')
        status, _, body = browser.request(location)
        require(status == 200, 'Native consent form must be available')
        data = hidden_fields(body)
        data.pop('scope', None)
        if consent:
            data['scope'] = 'openid'
        status, headers, _ = browser.request('/oauth2/authorize', data)
        require(status == 302, 'Consent must return a protocol redirect')
        location = headers.get('Location', '')
    require(location.startswith(callback + '?'), 'Authorization must use registered callback')
    response = urllib.parse.parse_qs(urllib.parse.urlsplit(location).query)
    require(response.get('state') == [values['state']], 'Authorization state must be preserved')
    return verifier, response


def exchange(browser, client, callback, code, verifier):
    status, headers, body = browser.request('/oauth2/token', dict(grant_type='authorization_code',
        client_id=client, redirect_uri=callback, code=code, code_verifier=verifier))
    return status, json.loads(body)


def claims(token):
    return json.loads(base64.urlsafe_b64decode(token.split('.')[1] + '==='))


def protected(path, text):
    path.write_text(text)
    path.chmod(0o600)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--ca', type=Path, required=True)
    parser.add_argument('--state-dir', type=Path, required=True)
    parser.add_argument('--phase', choices=['before-restart', 'after-restart'], default='before-restart')
    args = parser.parse_args()
    args.state_dir.mkdir(parents=True, exist_ok=True)
    args.state_dir.chmod(0o700)
    cookie_file = args.state_dir / 'cookies.txt'
    cookies = MozillaCookieJar(str(cookie_file))
    if args.phase == 'after-restart':
        cookies.load(ignore_discard=True, ignore_expires=True)
    browser = Browser(args.base, args.ca, cookies)
    status, headers, body = browser.request('/.well-known/jwks.json')
    require(status == 200, 'JWKS must be public')
    jwks = json.loads(body)
    require(jwks['keys'] and all('d' not in key and 'p' not in key for key in jwks['keys']), 'JWKS must contain public keys only')
    status, _, body = browser.request('/.well-known/openid-configuration')
    discovery = json.loads(body)
    require(status == 200 and discovery['issuer'] == args.base, 'Discovery issuer must match configured HTTPS origin')
    callback = os.environ.get('AUTH_TEST_CALLBACK', 'http://127.0.0.1:8309/callback')
    if args.phase == 'after-restart':
        previous = json.loads((args.state_dir / 'continuity.json').read_text())
        require(jwks == previous['jwks'], 'Restart must preserve signing keys')
        _, response = authorization(browser, 'test-browser', callback, prompt='none')
        require('code' in response, 'Redis session must survive process restart')
        status, _, _ = browser.request('/auth/v1/me', headers={'Authorization': 'Bearer ' + previous['access_token']})
        require(status == 200, 'Token issued before restart must still verify')
        logout = dict(id_token_hint=previous['id_token'],post_logout_redirect_uri=callback.replace('/callback','/logged-out'),state=secrets.token_urlsafe(16))
        tampered = dict(logout, post_logout_redirect_uri='https://unregistered.example/logged-out')
        status, headers, _ = browser.request('/connect/logout?' + urllib.parse.urlencode(tampered))
        require(status in {400,403} and 'Location' not in headers, 'Unregistered logout redirect must be rejected')
        status, headers, body = browser.request('/connect/logout?' + urllib.parse.urlencode(logout))
        if status == 200:
            fields = hidden_fields(body)
            status, headers, _ = browser.request('/connect/logout', fields)
        require(status == 302 and headers.get('Location','').startswith(logout['post_logout_redirect_uri']), 'OIDC logout must complete')
        _, response = authorization(browser, 'test-browser', callback, prompt='none')
        require(response.get('error') == ['login_required'], 'Logout must remove browser authentication')
        print('PASS: signing keys, authenticated session and token validation survived restart; OIDC logout and callback checks passed')
        return
    _, response = authorization(browser, 'test-browser', callback, prompt='none')
    require(response.get('error') == ['login_required'], 'Anonymous silent authorization must fail')
    verifier, values = new_request('test-browser', callback)
    status, headers, _ = browser.request(query(values))
    require(status == 302 and '/login' in headers.get('Location', ''), 'Anonymous authorization must require login')
    status, headers, body = browser.request('/login')
    require(status == 200, 'Explicitly enabled local login must be available')
    form = body.decode()
    csrf = re.search(r'name="_csrf"[^>]*value="([^"]+)"', form)
    require(csrf, 'Login form must include CSRF token')
    before = next(cookie.value for cookie in browser.cookies if cookie.name == '__Host-mars-session')
    session_cookie = next(cookie for cookie in browser.cookies if cookie.name == '__Host-mars-session')
    require(session_cookie.secure and session_cookie.path == '/' and not session_cookie.domain_specified,
            'Session cookie must be Secure, host-only and use root path')
    require('HttpOnly' in session_cookie._rest and session_cookie._rest.get('SameSite') == 'Lax', 'Cookie must be HttpOnly and SameSite=Lax')
    old_cookies = MozillaCookieJar()
    for cookie in browser.cookies:
        old_cookies.set_cookie(copy.copy(cookie))
    login = dict(username=os.environ['MARS_AUTH_LOCAL_LOGIN_USER_ID'], password=os.environ['MARS_AUTH_LOCAL_LOGIN_PASSWORD'])
    status, _, _ = browser.request('/login', login)
    require(status == 403, 'Login without CSRF must be rejected')
    if os.environ.get('MARS_MANAGEMENT_PASSWORD'):
        status, headers, _ = browser.request('/login', dict(username=os.environ['MARS_MANAGEMENT_USERNAME'],password=os.environ['MARS_MANAGEMENT_PASSWORD'],_csrf=csrf.group(1)))
        require(status == 302 and 'error' in headers.get('Location',''), 'Management credentials must not authenticate a user login')
    login['_csrf'] = csrf.group(1)
    status, headers, _ = browser.request('/login', login)
    require(status == 302, 'Valid local login must redirect')
    after = next(cookie.value for cookie in browser.cookies if cookie.name == '__Host-mars-session')
    require(before != after, 'Login must replace the session identifier')
    _, stale = authorization(Browser(args.base, args.ca, old_cookies), 'test-browser', callback, prompt='none')
    require(stale.get('error') == ['login_required'], 'Pre-login session identifier must be invalid')
    verifier, response = authorization(browser, 'test-browser', callback)
    require('code' in response, 'Authenticated authorization must issue code')
    code = response['code'][0]
    status, token = exchange(browser, 'test-browser', callback, code, verifier)
    require(status == 200 and 'access_token' in token and 'id_token' in token, 'Code exchange must issue access and ID tokens')
    require('refresh_token' not in token, 'Browser must not receive refresh token')
    access = claims(token['access_token'])
    allowed = {'iss','sub','aud','exp','iat','jti','client_id','sid','tenant_id','scope'}
    require(set(access) == allowed, 'Access token claims must match the identity allowlist')
    require(access['sub'] == login['username'] and access['tenant_id'] == 'default', 'Identity must use the stable account identifier')
    require(access['exp'] - access['iat'] == 900, 'Access token lifetime must be 15 minutes')
    require('mars-cloud-order-service' not in access['aud'] and 'mars-cloud-upms-service' not in access['aud'], 'Product audience must exclude order and permissions')
    identity = claims(token['id_token'])
    browser_sid = base64.b64decode(after).decode()
    expected_id_sid = base64.urlsafe_b64encode(hashlib.sha256(browser_sid.encode()).digest()).decode().rstrip('=')
    require(access['sid'] == browser_sid and identity['sid'] == expected_id_sid, 'Access and ID tokens must use their respective session identifiers')
    require(set(identity) <= {'iss','sub','aud','exp','iat','jti','sid','auth_time','azp','nonce'}, 'ID token must exclude profile and permission claims')
    status, _, body = browser.request('/auth/v1/me', headers={'Authorization': 'Bearer ' + token['access_token']})
    require(status == 200 and login['username'] in body.decode(), 'Bearer identity must reach the business API')
    require(next(c.value for c in browser.cookies if c.name == '__Host-mars-session') == after, 'Stateless bearer calls must not change the browser session')
    status, _, _ = browser.request('/auth/v1/me')
    require(status == 401, 'Login cookie alone must not authenticate a business API')
    status, _, body = browser.request('/userinfo', headers={'Authorization': 'Bearer ' + token['access_token']})
    require(status == 200 and json.loads(body) == {'sub': login['username']}, 'Userinfo must expose only sub (status=' + str(status) + ', error=' + str(json.loads(body).get('error_description', ''))[:250].replace(token['access_token'], '<token>') + ')')
    status, _, _ = browser.request('/auth/v1/me', headers={'Authorization': 'Bearer ' + token['id_token']})
    require(status == 401, 'ID token must not authenticate a business API')
    status, _ = exchange(browser, 'test-browser', callback, code, verifier)
    require(status == 400, 'Used code must not be exchangeable again')
    print('PASS: initial login, token exchange, bearer isolation, userinfo and replay rejection')
    _, response = authorization(browser, 'test-browser', callback, code_challenge_method='plain')
    require('error' in response, 'Plain PKCE must be rejected')
    verifier, response = authorization(browser, 'test-browser', callback)
    code = response['code'][0]
    def redeem(_):
        return exchange(Browser(args.base, args.ca), 'test-browser', callback, code, verifier)[0]
    with ThreadPoolExecutor(max_workers=4) as executor:
        results = list(executor.map(redeem, range(4)))
    require(results.count(200) == 1 and results.count(400) == 3, 'Concurrent code exchange must succeed exactly once')
    for bad in ['', secrets.token_urlsafe(48)]:
        verifier, response = authorization(browser, 'test-browser', callback)
        status, _ = exchange(browser, 'test-browser', callback, response['code'][0], bad)
        require(status == 400, 'Missing or incorrect PKCE verifier must be rejected')
    verifier, response = authorization(browser, 'test-browser', callback)
    require(exchange(browser, 'test-native', callback, response['code'][0], verifier)[0] == 400, 'Code must be bound to its client')
    for overrides in [dict(redirect_uri='https://unregistered.example/callback'),dict(client_id='unknown-client')]:
        _, parameters = new_request('test-browser', callback, **overrides)
        status, headers, _ = browser.request(query(parameters))
        require(status in {400,403} and 'Location' not in headers, 'Invalid client or callback must not redirect')
    base_aud = {'mars-cloud-gateway','mars-cloud-auth-service','mars-cloud-product-service','mars-cloud-notice-service'}
    for client in ['portal','flippo-book','wonder-lab','english-word-card','console','csthink-assistant']:
        redirect = callback if client == 'csthink-assistant' else 'https://' + client + '.example/callback'
        verifier, response = authorization(browser, client, redirect)
        require('code' in response, 'Configured client must authorize: ' + client)
        status, issued = exchange(browser, client, redirect, response['code'][0], verifier)
        require(status == 200, 'Configured client must exchange code: ' + client)
        actual = claims(issued['access_token'])
        expected = base_aud | ({'mars-cloud-order-service','mars-cloud-support-service'} if client == 'portal' else {'mars-cloud-order-service','mars-cloud-upms-service'} if client == 'console' else set())
        require(set(actual['aud']) == expected, 'Audience must exactly match client policy: ' + client)
        require(claims(issued['id_token'])['aud'] in [client, [client]], 'ID token audience must name its client')
        if client != 'csthink-assistant':
            require('refresh_token' not in issued, 'Browser clients must not receive refresh tokens')
        else:
            require(actual['sid'] != browser_sid and claims(issued['id_token'])['sid'] == expected_id_sid, 'Native business session must be distinct from its browser OIDC session')
            _, declined = authorization(browser, client, redirect, consent=False)
            require(declined.get('error') == ['access_denied'], 'Native consent cancellation must not reuse historical consent')
            _, silent = authorization(browser, client, redirect, prompt='none')
            require(silent.get('error') == ['consent_required'], 'Native consent must be explicit each time')
    verifier, response = authorization(browser, 'test-browser', callback)
    status, continuity = exchange(browser, 'test-browser', callback, response['code'][0], verifier)
    require(status == 200, 'Fresh continuity token must be available')
    browser.cookies.save(ignore_discard=True, ignore_expires=True)
    cookie_file.chmod(0o600)
    protected(args.state_dir / 'continuity.json', json.dumps(dict(jwks=jwks, access_token=continuity['access_token'], id_token=continuity['id_token'])))
    print('PASS: HTTPS discovery, login, CSRF, session replacement, PKCE, token claims, business API isolation, userinfo and concurrent code exchange')


if __name__ == '__main__':
    main()
