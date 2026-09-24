#!/usr/bin/env python3
"""Generate private local HTTPS and test-login inputs without changing system trust."""
import argparse
import base64
import json
from pathlib import Path
import secrets
import shlex
import subprocess
from verify_auth import protected, require


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port',type=int,default=8101)
    parser.add_argument('--callback-port',type=int,default=8309)
    parser.add_argument('--name',default='auth-local')
    args=parser.parse_args()
    require(1024<=args.port<=64535 and 1024<=args.callback_port<=65535,'Invalid local port')
    require(args.name.replace('-','').isalnum(),'Use an alphanumeric local environment name')
    state=Path(__file__).resolve().parents[2]/'dev/.local'/args.name
    require(not state.is_symlink(),'Local state must not be a symlink')
    state.mkdir(parents=True,exist_ok=True,mode=0o700)
    state.chmod(0o700)
    output=state/'local.env'
    require(not output.exists(),'Local inputs already exist; reuse them to preserve signing keys and credentials')
    password=secrets.token_urlsafe(32)
    protected(state/'tls-password',password)
    protected(state/'extensions.cnf','subjectAltName=IP:127.0.0.1\nextendedKeyUsage=serverAuth\nkeyUsage=digitalSignature,keyEncipherment\n')
    def run(*arguments):
        subprocess.run(arguments,check=True,cwd=state,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    run('openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout','ca.key','-out','ca.crt','-days','7','-subj','/CN=Local authentication verification CA')
    run('openssl','req','-newkey','rsa:2048','-nodes','-keyout','server.key','-out','server.csr','-subj','/CN=127.0.0.1')
    run('openssl','x509','-req','-in','server.csr','-CA','ca.crt','-CAkey','ca.key','-CAcreateserial','-out','server.crt','-days','7','-extfile','extensions.cnf')
    run('openssl','pkcs12','-export','-inkey','server.key','-in','server.crt','-certfile','ca.crt','-name','auth-local','-out','server.p12','-passout','file:tls-password')
    clients=['portal','flippo-book','wonder-lab','english-word-card','console','csthink-assistant','test-browser','test-native']
    lines=['mars:','  auth:','    clients:']
    for client in clients:
        base=f'http://127.0.0.1:{args.callback_port}' if client in {'csthink-assistant','test-browser','test-native'} else 'https://'+client+'.example'
        lines.extend(['      '+client+':','        redirect-uris: ["'+base+'/callback"]','        post-logout-redirect-uris: ["'+base+'/logged-out"]'])
    protected(state/'clients.yml','\n'.join(lines)+'\n')
    env=dict(SERVER_PORT=str(args.port),SERVER_ADDRESS='127.0.0.1',SPRING_PROFILES_ACTIVE='local',
        MARS_AUTH_ISSUER=f'https://127.0.0.1:{args.port}',MARS_AUTH_JWK_ENCRYPTION_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
        MARS_AUTH_JWK_ENCRYPTION_KEY_ID='local-v1',MARS_AUTH_LOCAL_LOGIN_ENABLED='true',
        MARS_AUTH_LOCAL_LOGIN_USER_ID=str(secrets.randbelow(10**17)+10**17),MARS_AUTH_LOCAL_LOGIN_PASSWORD=secrets.token_urlsafe(32),
        SERVER_SSL_ENABLED='true',SERVER_SSL_KEY_STORE=str(state/'server.p12'),SERVER_SSL_KEY_STORE_PASSWORD=password,
        SPRING_CLOUD_NACOS_DISCOVERY_SECURE='true',SPRING_CONFIG_ADDITIONAL_LOCATION='file:'+str(state/'clients.yml'),
        AUTH_VERIFY_CA=str(state/'ca.crt'),AUTH_TEST_CALLBACK=f'http://127.0.0.1:{args.callback_port}/callback')
    protected(output,''.join(k+'='+shlex.quote(v)+'\n' for k,v in env.items()))
    for path in state.iterdir():
        path.chmod(0o600)
    print('Private local inputs:',output)
    print('Certificates expire after seven days; no system or browser trust store was changed.')


if __name__=='__main__':
    main()
