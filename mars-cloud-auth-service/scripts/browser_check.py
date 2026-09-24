#!/usr/bin/env python3
"""Receive one native test authorization on a literal loopback callback without logging tokens."""
import argparse
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
import urllib.parse
from verify_auth import Browser, exchange, new_request, protected, query


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',required=True)
    parser.add_argument('--ca',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--port',type=int,default=8309)
    args=parser.parse_args()
    callback=f'http://127.0.0.1:{args.port}/callback'
    verifier,parameters=new_request('test-native',callback)
    protected(args.output,args.base+query(parameters))
    result={'passed':False}
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):
            pass
        def do_GET(self):
            values=urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            ok=False
            if urllib.parse.urlsplit(self.path).path=='/callback' and values.get('state')==[parameters['state']] and 'code' in values:
                status,token=exchange(Browser(args.base,args.ca),'test-native',callback,values['code'][0],verifier)
                ok=status==200 and 'access_token' in token and 'id_token' in token
            result['passed']=ok
            self.send_response(200 if ok else 400)
            self.send_header('Content-Type','text/html; charset=utf-8')
            self.send_header('Cache-Control','no-store')
            self.send_header('Referrer-Policy','no-referrer')
            self.end_headers()
            message='Authorization verified' if ok else 'Authorization failed'
            self.wfile.write(('<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">'
                '<title>'+message+'</title><main><h1>'+message+'</h1><p>You may close this local verification window.</p></main></html>').encode())
    with HTTPServer(('127.0.0.1',args.port),Handler) as server:
        server.timeout=300
        print('Local callback ready; authorization URL written to the protected output file.',flush=True)
        server.handle_request()
    protected(args.output.with_suffix('.result.json'),json.dumps(result))
    if not result['passed']:
        raise SystemExit('Browser authorization did not complete')
    print('PASS: browser login, native consent, callback state and PKCE exchange')


if __name__=='__main__':
    main()
