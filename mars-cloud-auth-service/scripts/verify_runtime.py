#!/usr/bin/env python3
"""Run real-process authentication checks using explicitly prepared verification databases."""
from contextlib import contextmanager
import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.request
from verify_auth import Browser, protected, require
from resume_sms_budget import command as redis_command

MODULE=Path(__file__).resolve().parents[1]
ROOT=MODULE.parent
JAVA=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
FLAGS=['--sun-misc-unsafe-memory-access=allow','--enable-native-access=ALL-UNNAMED']
STATE=ROOT/'dev/.local'/('auth-acceptance-'+str(time.time_ns()))
STATE.mkdir(parents=True,mode=0o700)
ENV=dict(os.environ)


def required(name):
    value=ENV.get(name,'')
    require(bool(value),'Missing '+name)
    return value


def environment_file(name):
    path=Path(required(name)).resolve()
    require(not path.is_symlink() and path.stat().st_mode&0o077==0,'Verification environment must have private permissions')
    result={}
    for line in path.read_text().splitlines():
        if not line.strip() or line.startswith('#'):
            continue
        key,value=line.split('=',1)
        parsed=shlex.split(value)
        require(len(parsed)==1,'Environment values must be shell-quoted assignments')
        result[key]=parsed[0]
    return result


def probe(class_name,env,*arguments):
    classpath=os.pathsep.join([str(MODULE/'target/test-classes'),str(MODULE/'target/classes'),(MODULE/'target/test-classpath.txt').read_text().strip()])
    name=class_name.rsplit('.',1)[-1]+'-'+str(time.time_ns())
    with (STATE/(name+'.log')).open('w') as log:
        process=subprocess.run([JAVA,*FLAGS,'-cp',classpath,class_name,*arguments],env=env,stdout=log,stderr=subprocess.STDOUT,timeout=90)
    require(process.returncode==0,name+' failed; inspect private verification logs')
    print('PASS:',class_name.rsplit('.',1)[-1],flush=True)


def management(port,path,credentials=False):
    headers={}
    if credentials:
        encoded=base64.b64encode((required('MARS_MANAGEMENT_USERNAME')+':'+required('MARS_MANAGEMENT_PASSWORD')).encode()).decode()
        headers['Authorization']='Basic '+encoded
    try:
        response=urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{port+1000}'+path,headers=headers),timeout=2)
    except urllib.error.HTTPError as error:
        response=error
    except (urllib.error.URLError,TimeoutError):
        return 0
    with response:
        return response.status


JAR=MODULE/'target/mars-cloud-auth-service.jar'
require(JAR.is_file(),'Build the authentication module first')
APP=STATE/('app-'+hashlib.sha256(JAR.read_bytes()).hexdigest()+'.jar')
shutil.copyfile(JAR,APP)
PORT=int(required('SERVER_PORT'))
BASE=required('MARS_AUTH_ISSUER')
CA=Path(required('AUTH_VERIFY_CA')).resolve()
require(BASE==f'https://127.0.0.1:{PORT}','Verification requires the exact local HTTPS issuer')
require(required('AUTH_VERIFY_DATABASE') in required('SPRING_DATASOURCE_URL') and '_verify_' in required('AUTH_VERIFY_DATABASE'),'Use a dedicated verification database')


@contextmanager
def running(name,env,success=True,expected_error=None):
    port=int(env['SERVER_PORT'])
    for number in (port,port+1000):
        with socket.socket() as sock:
            sock.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            sock.bind(('127.0.0.1',number))
    path=STATE/(name+'.log')
    with path.open('w') as log:
        process=subprocess.Popen([JAVA,*FLAGS,'-jar',str(APP)],env=env,stdout=log,stderr=subprocess.STDOUT)
        try:
            for _ in range(120):
                if process.poll() is not None or management(port,'/actuator/health/readiness')==200:
                    break
                time.sleep(.5)
            if success:
                require(process.poll() is None and management(port,'/actuator/health/readiness')==200,name+' failed readiness')
            else:
                require(process.poll() is not None and process.returncode!=0,name+' must reject startup')
                require(expected_error in path.read_text(),name+' failed for an unexpected reason')
            yield path
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=30)


def verify_log(path):
    allowed={
        'Using MySQL 8.4 which is newer than the version Flyway has been verified with. The latest verified version of MySQL is 8.1.',
        "Invalidated authorization token(s) previously issued to registered client 'test-browser'",
        "Invalidated authorization code used by registered client 'test-native'",
    }
    # The version notice is retained because dependency versions come from the shared BOM.
    # Invalidation notices are the deliberately exercised code-replay and cross-client tests.
    for line in path.read_text().splitlines():
        try:
            event=json.loads(line)
        except ValueError:
            require('Exception in thread' not in line,'Unexpected process exception')
            continue
        if not isinstance(event,dict):
            continue
        level=event.get('log',{}).get('level',event.get('level'))
        if level in {'WARN','ERROR'}:
            require(event.get('message') in allowed,'Unexpected warning or error; inspect private verification logs')
    for name in ['MARS_AUTH_LOCAL_LOGIN_PASSWORD','MARS_AUTH_JWK_ENCRYPTION_KEY','MARS_AUTH_SMS_HMAC_KEY','SPRING_DATASOURCE_PASSWORD','SPRING_DATA_REDIS_PASSWORD','MARS_MANAGEMENT_PASSWORD']:
        value=ENV.get(name)
        if value:
            require(value not in path.read_text(),'A credential appeared in application logs')


def protocol(phase):
    subprocess.run(['python3',str(MODULE/'scripts/verify_auth.py'),'--base',BASE,'--ca',str(CA),'--state-dir',str(STATE/'protocol'),'--phase',phase],env=ENV,check=True,timeout=90)


def budget_resume():
    today=dt.datetime.now(dt.timezone.utc).date().isoformat()
    budget_key='mars:auth:sms:budget:'+today
    pause_key='mars:auth:sms:budget:paused'
    alert_key='mars:auth:sms:budget:alert'
    with socket.create_connection((required('SPRING_DATA_REDIS_HOST'),int(required('SPRING_DATA_REDIS_PORT'))),timeout=5) as connection:
        connection.settimeout(5)
        if ENV.get('SPRING_DATA_REDIS_PASSWORD'):
            redis_command(connection,'AUTH',ENV['SPRING_DATA_REDIS_PASSWORD'])
        redis_command(connection,'SELECT',required('SPRING_DATA_REDIS_DATABASE'))
        original=redis_command(connection,'GET',budget_key)
        original_ttl=redis_command(connection,'PTTL',budget_key)
        require(redis_command(connection,'GET',pause_key) is None and
                redis_command(connection,'GET',alert_key) is None,
                'Budget resume probe requires unpaused isolated Redis')
        audit=STATE/'sms-budget-audit.jsonl'
        command_line=['python3',str(MODULE/'scripts/resume_sms_budget.py'),'--operator','acceptance',
                      '--reason','verification','--audit-file',str(audit)]
        try:
            redis_command(connection,'SET',pause_key,'1')
            redis_command(connection,'SET',budget_key,ENV.get('MARS_AUTH_SMS_DAILY_BUDGET','2000'))
            denied=subprocess.run(command_line,env=ENV,capture_output=True,text=True,timeout=10)
            require(denied.returncode!=0 and redis_command(connection,'GET',pause_key)=='1',
                    'Resume must reject a still-exhausted budget')
            redis_command(connection,'SET',budget_key,'0')
            accepted=subprocess.run(command_line,env=ENV,capture_output=True,text=True,timeout=10)
            require(accepted.returncode==0 and redis_command(connection,'GET',pause_key) is None,
                    'Operator resume must clear pause after budget check')
            actions=[json.loads(line)['action'] for line in audit.read_text().splitlines()]
            require(actions==['resume_sms_budget_requested','resume_sms_budget_rejected',
                              'resume_sms_budget_requested','resume_sms_budget_completed'],
                    'Budget resume must leave complete private audit records')
        finally:
            if original is None: redis_command(connection,'DEL',budget_key)
            else:
                redis_command(connection,'SET',budget_key,original)
                if original_ttl>=0: redis_command(connection,'PEXPIRE',budget_key,original_ttl)
            redis_command(connection,'DEL',pause_key,alert_key)
    print('PASS: SMS budget resume rejection, authorized recovery and private audit',flush=True)


production=environment_file('AUTH_PRODUCTION_ENV_FILE')
nonempty=environment_file('AUTH_NONEMPTY_ENV_FILE')
probe('com.mars.cloud.service.auth.configuration.MigrationProbe',{**ENV,**production})
probe('com.mars.cloud.service.auth.configuration.MigrationProbe',{**ENV,**nonempty},'nonempty')
with running('local-before',ENV) as log:
    probe('com.mars.cloud.service.auth.verification.DatabaseProbe',ENV)
    require(management(PORT,'/actuator/info')==401,'Management info requires credentials')
    require(management(PORT,'/actuator/info',True)==200,'Management credentials must work only on management endpoints')
    subprocess.run(['python3',str(MODULE/'scripts/verify_sms.py'),'--base',BASE,'--ca',str(CA),
                    '--audit-output',str(STATE/'sms-account-id')],env=ENV,check=True,timeout=90)
    probe('com.mars.cloud.service.auth.verification.SmsAuditProbe',ENV,str(STATE/'sms-account-id'))
    protocol('before-restart')
    probe('com.mars.cloud.service.auth.configuration.SessionProbe',ENV)
verify_log(log)
with running('local-after',ENV) as log:
    protocol('after-restart')
verify_log(log)
probe('com.mars.cloud.service.auth.verification.SmsRiskProbe',ENV)
budget_resume()
clients=['portal','flippo-book','wonder-lab','english-word-card','console','csthink-assistant']
lines=['mars:','  auth:','    clients:']
for client in clients:
    origin='http://127.0.0.1:8309' if client=='csthink-assistant' else 'https://'+client+'.example'
    lines.extend(['      '+client+':','        redirect-uris: ["'+origin+'/callback"]','        post-logout-redirect-uris: ["'+origin+'/logged-out"]'])
config=STATE/'production-clients.yml'
protected(config,'\n'.join(lines)+'\n')
prod={**ENV,**production,'SERVER_PORT':str(PORT+10),'SPRING_PROFILES_ACTIVE':'production','MARS_AUTH_ISSUER':'https://auth.example','MARS_AUTH_LOCAL_LOGIN_ENABLED':'false','MARS_AUTH_SMS_MOCK_ENABLED':'false','SPRING_CONFIG_ADDITIONAL_LOCATION':'file:'+str(config)}
with running('production',prod) as log:
    status,_,_=Browser(f'https://127.0.0.1:{PORT+10}',CA).request('/login')
    require(status==503,'Production without an SMS sender must fail closed')
verify_log(log)
for name,overrides,message in [
    ('test-data',{key:ENV[key] for key in ['SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD']},'Test data is forbidden outside local/test'),
    ('local-login',{'MARS_AUTH_LOCAL_LOGIN_ENABLED':'true'},'Local login is restricted to local/test profiles'),
    ('mock-sms',{'MARS_AUTH_SMS_MOCK_ENABLED':'true'},'Mock SMS requires local/test'),
    ('wrong-key',{'MARS_AUTH_JWK_ENCRYPTION_KEY':base64.b64encode(os.urandom(32)).decode()},'Cannot decrypt signing-key material'),
    ('missing-key',{'MARS_AUTH_JWK_ENCRYPTION_KEY':''},'A 256-bit signing-key encryption key is required'),
]:
    with running(name,{**prod,**overrides},False,message):
        print('PASS: startup rejection',name,flush=True)
print('Authentication acceptance passed. Private logs:',STATE)
