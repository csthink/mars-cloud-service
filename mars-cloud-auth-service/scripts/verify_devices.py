#!/usr/bin/env python3
"""Exercise the device limit with real HTTPS logins; with --gateway, prove that evicted sessions are rejected there within five seconds."""
import argparse
import json
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.request
from verify_auth import Browser, authorization, claims, exchange, new_request, query, require

GATEWAY_HEADERS = {'Host': 'api.flippoabc.com', 'X-Forwarded-For': '203.0.113.10', 'X-Forwarded-Proto': 'https'}


def login(base, ca, callback):
    browser = Browser(base, ca)
    _, values = new_request('test-browser', callback)
    status, headers, _ = browser.request(query(values))
    require(status == 302 and '/login' in headers.get('Location', ''), 'Anonymous authorization must require login')
    status, _, body = browser.request('/login')
    csrf = re.search(r'name="_csrf"[^>]*value="([^"]+)"', body.decode())
    require(status == 200 and csrf, 'Login form must be available with a CSRF token')
    status, _, _ = browser.request('/login', dict(username=os.environ['MARS_AUTH_LOCAL_LOGIN_USER_ID'],
                                                  password=os.environ['MARS_AUTH_LOCAL_LOGIN_PASSWORD'], _csrf=csrf.group(1)))
    require(status == 302, 'Local login must succeed')
    return browser


def token(browser, client, callback):
    verifier, response = authorization(browser, client, callback)
    require('code' in response, 'Authenticated authorization must issue a code for ' + client)
    status, issued = exchange(browser, client, callback, response['code'][0], verifier)
    require(status == 200 and 'access_token' in issued, 'Code exchange must issue an access token for ' + client)
    return issued['access_token']


def silent(browser, callback):
    _, response = authorization(browser, 'test-browser', callback, prompt='none')
    if 'code' in response:
        return 'alive'
    require(response.get('error') == ['login_required'], 'Silent authorization must either issue a code or require login')
    return 'evicted'


def gateway_status(gateway, access_token):
    request = urllib.request.Request(gateway + '/auth/v1/me', headers={**GATEWAY_HEADERS, 'Authorization': 'Bearer ' + access_token})
    try:
        response = urllib.request.urlopen(request, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = response.read()
        code = None
        if response.status != 200:
            try:
                code = json.loads(body).get('code')
            except ValueError:
                code = None
        return response.status, code


def expect_gateway(gateway, access_token, status, label):
    actual, code = gateway_status(gateway, access_token)
    require(actual == status, label + ': expected HTTP ' + str(status) + ', got ' + str(actual) + ' code ' + str(code))


def wait_rejected(gateway, access_token, evicted_at, label):
    deadline = evicted_at + 8
    while True:
        status, code = gateway_status(gateway, access_token)
        now = time.monotonic()
        if status == 401:
            require(code == '62007', label + ': revoked session must return 62007, got ' + str(code))
            require(now - evicted_at <= 5, label + ': rejection took ' + format(now - evicted_at, '.2f') + 's, expected at most 5s')
            return
        require(now < deadline, label + ': evicted session still accepted after eight seconds (status ' + str(status) + ')')
        time.sleep(0.25)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--ca', type=Path, required=True)
    parser.add_argument('--gateway', help='Gateway origin; when given, evicted tokens must be rejected there')
    args = parser.parse_args()
    callback = os.environ.get('AUTH_TEST_CALLBACK', 'http://127.0.0.1:8309/callback')
    gateway = args.gateway.rstrip('/') if args.gateway else None

    # Three browsers, then a native authorization from the third: the first browser is evicted.
    first = login(args.base, args.ca, callback)
    first_token = token(first, 'test-browser', callback)
    if gateway:
        expect_gateway(gateway, first_token, 200, 'first browser before eviction')
    second = login(args.base, args.ca, callback)
    second_token = token(second, 'test-browser', callback)
    third = login(args.base, args.ca, callback)
    third_token = token(third, 'test-browser', callback)
    native_token = token(third, 'test-native', callback)
    evicted_at = time.monotonic()
    require(claims(native_token)['sid'] != claims(third_token)['sid'], 'Native device must have its own session identifier')
    require(silent(first, callback) == 'evicted', 'The least recently seen browser must be evicted by the native device')
    require(silent(second, callback) == 'alive' and silent(third, callback) == 'alive', 'The other browsers must stay logged in')
    if gateway:
        wait_rejected(gateway, first_token, evicted_at, 'evicted browser')
        for label, value in [('second browser', second_token), ('third browser', third_token), ('native device', native_token)]:
            expect_gateway(gateway, value, 200, label + ' after the eviction')
    print('PASS: fourth device evicted the least recently seen browser; the other three devices stay usable', flush=True)

    # The native device is now the least recently seen one: a new browser login evicts it.
    fourth = login(args.base, args.ca, callback)
    fourth_token = token(fourth, 'test-browser', callback)
    evicted_at = time.monotonic()
    require(silent(second, callback) == 'alive' and silent(third, callback) == 'alive', 'Browsers newer than the native device must survive')
    if gateway:
        wait_rejected(gateway, native_token, evicted_at, 'evicted native device')
        for label, value in [('second browser', second_token), ('third browser', third_token), ('fourth browser', fourth_token)]:
            expect_gateway(gateway, value, 200, label + ' after the native eviction')
    print('PASS: a new browser evicted the native device whose refresh chain was the least recently seen', flush=True)

    # One browser using several sites is one device: two clients share the session identifier and the next login evicts one device only.
    portal_token = token(fourth, 'portal', 'https://portal.example/callback')
    book_token = token(fourth, 'flippo-book', 'https://flippo-book.example/callback')
    require(claims(portal_token)['sid'] == claims(book_token)['sid'] == claims(fourth_token)['sid'], 'Sites in one browser must share the session identifier')
    fifth = login(args.base, args.ca, callback)
    evicted_at = time.monotonic()
    require(silent(second, callback) == 'evicted', 'The least recently seen browser is evicted')
    require(silent(third, callback) == 'alive' and silent(fourth, callback) == 'alive' and silent(fifth, callback) == 'alive', 'Exactly one device is evicted for the fifth login')
    if gateway:
        wait_rejected(gateway, second_token, evicted_at, 'evicted second browser')
        for label, value in [('third browser', third_token), ('portal token', portal_token), ('product token', book_token)]:
            expect_gateway(gateway, value, 200, label + ' after the fifth login')
    print('PASS: portal and product site in one browser count as one device; the fifth login evicted one device', flush=True)


if __name__ == '__main__':
    main()
