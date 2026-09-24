#!/usr/bin/env python3
"""Resume a paused SMS budget after an operator verifies the current UTC day count."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import socket
import ssl
import stat


def command(connection, *parts):
    payload=b'*'+str(len(parts)).encode()+b'\r\n'
    for part in parts:
        value=str(part).encode()
        payload+=b'$'+str(len(value)).encode()+b'\r\n'+value+b'\r\n'
    connection.sendall(payload)
    lead=connection.recv(1)
    if not lead:
        raise RuntimeError('Redis connection closed')
    def line():
        data=bytearray()
        while not data.endswith(b'\r\n'):
            value=connection.recv(1)
            if not value:
                raise RuntimeError('Redis connection closed')
            data+=value
        return bytes(data[:-2])
    value=line()
    if lead==b'-':
        raise RuntimeError('Redis rejected the operation')
    if lead==b':':
        return int(value)
    if lead==b'+':
        return value.decode()
    if lead==b'$':
        size=int(value)
        if size<0:
            return None
        data=b''
        while len(data)<size+2:
            chunk=connection.recv(size+2-len(data))
            if not chunk:
                raise RuntimeError('Redis connection closed')
            data+=chunk
        return data[:-2].decode()
    raise RuntimeError('Unexpected Redis response')


SCRIPT="""
if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
if tonumber(redis.call('GET', KEYS[2]) or '0') >= tonumber(ARGV[1]) then return 2 end
redis.call('DEL', KEYS[1], KEYS[3])
return 1
"""


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--operator',required=True)
    parser.add_argument('--reason',required=True)
    parser.add_argument('--audit-file',required=True,type=Path)
    args=parser.parse_args()
    if not args.operator.strip() or not args.reason.strip() or len(args.operator)>120 or len(args.reason)>500:
        parser.error('Operator and reason must be bounded and nonempty')
    if '\n' in args.operator+args.reason or '\r' in args.operator+args.reason:
        parser.error('Operator and reason must be single-line')
    budget=int(os.environ.get('MARS_AUTH_SMS_DAILY_BUDGET','2000'))
    if budget<=0:
        parser.error('MARS_AUTH_SMS_DAILY_BUDGET must be positive')
    audit=args.audit_file.expanduser().absolute()
    if not audit.parent.is_dir() or audit.parent.is_symlink() or stat.S_IMODE(audit.parent.stat().st_mode)&0o077:
        parser.error('Audit directory must already exist with private permissions')
    if audit.exists() and (audit.is_symlink() or stat.S_IMODE(audit.stat().st_mode)&0o077):
        parser.error('Audit file must be private and not a symlink')
    today=dt.datetime.now(dt.timezone.utc).date().isoformat()
    descriptor=os.open(audit,os.O_WRONLY|os.O_APPEND|os.O_CREAT|os.O_NOFOLLOW,0o600)
    with os.fdopen(descriptor,'a') as output:
        def record(action):
            entry=dict(timestamp=dt.datetime.now(dt.timezone.utc).isoformat(),operator=args.operator,
                       reason=args.reason,action=action,date=today)
            output.write(json.dumps(entry,ensure_ascii=False)+'\n')
            output.flush()
            os.fsync(output.fileno())
        record('resume_sms_budget_requested')
        host=os.environ.get('SPRING_DATA_REDIS_HOST','127.0.0.1')
        port=int(os.environ.get('SPRING_DATA_REDIS_PORT','6379'))
        with socket.create_connection((host,port),timeout=5) as raw:
            connection=ssl.create_default_context().wrap_socket(raw,server_hostname=host) if os.environ.get('SPRING_DATA_REDIS_SSL_ENABLED')=='true' else raw
            connection.settimeout(5)
            password=os.environ.get('SPRING_DATA_REDIS_PASSWORD','')
            if password:
                user=os.environ.get('SPRING_DATA_REDIS_USERNAME','')
                command(connection,*(['AUTH',user,password] if user else ['AUTH',password]))
            command(connection,'SELECT',int(os.environ.get('SPRING_DATA_REDIS_DATABASE','0')))
            result=command(connection,'EVAL',SCRIPT,3,'mars:auth:sms:budget:paused',
                           'mars:auth:sms:budget:'+today,'mars:auth:sms:budget:alert',budget)
        if result!=1:
            record('resume_sms_budget_rejected')
            raise SystemExit('Budget remains paused: '+('pause marker absent' if result==0 else 'current day is still at the limit'))
        record('resume_sms_budget_completed')
    print('SMS budget resumed for operator',args.operator)


if __name__=='__main__':
    main()
