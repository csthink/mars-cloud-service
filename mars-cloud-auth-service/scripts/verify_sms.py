#!/usr/bin/env python3
"""Exercise SMS login against the real HTTPS process and protected mock outbox."""
import argparse
import base64
import copy
from http.cookiejar import MozillaCookieJar
import json
import os
from pathlib import Path
import re
import secrets
from verify_auth import Browser, authorization, claims, exchange, new_request, protected, query, require


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',required=True)
    parser.add_argument('--ca',type=Path,required=True)
    parser.add_argument('--audit-output',type=Path)
    args=parser.parse_args()
    outbox=Path(os.environ['MARS_AUTH_SMS_MOCK_OUTBOX'])
    require(outbox.is_file() and outbox.stat().st_mode&0o077==0,'Mock outbox must be private')
    browser=Browser(args.base,args.ca)
    callback=os.environ.get('AUTH_TEST_CALLBACK','http://127.0.0.1:8309/callback')
    verifier,values=new_request('test-browser',callback)
    status,headers,_=browser.request(query(values))
    require(status==302 and '/login' in headers.get('Location',''),'Anonymous authorization must reach login')
    status,_,body=browser.request('/login')
    require(status==200,'Local login page must load')
    token=re.search(rb'name="_csrf"[^>]*value="([^"]+)"',body)
    require(token,'Local login page must expose the CSRF token')
    csrf=token.group(1).decode()
    before=next(cookie.value for cookie in browser.cookies if cookie.name=='__Host-mars-session')
    old_cookies=MozillaCookieJar()
    for cookie in browser.cookies:
        old_cookies.set_cookie(copy.copy(cookie))
    phone='+1555'+''.join(str(secrets.randbelow(10)) for _ in range(7))
    send=dict(phone=phone,_csrf=csrf)
    path='/login/sms/send'
    status,_,_=browser.request(path,dict(phone=phone),headers={'Origin':args.base})
    require(status==403,'SMS send without CSRF must be rejected')
    status,_,_=browser.request(path,send)
    require(status==403,'SMS send without Origin must be rejected')
    status,_,_=browser.request(path,send,headers={'Origin':'https://invalid.example'})
    require(status==403,'Cross-origin SMS send must be rejected')
    status,_,_=browser.request(path,send,headers={'Origin':args.base,'Sec-Fetch-Site':'cross-site'})
    require(status==403,'Cross-site SMS send must be rejected')
    status,headers,body=browser.request('/login/captcha',dict(purpose='SEND',_csrf=csrf),headers={'Origin':args.base})
    require(status==200 and headers.get('Cache-Control','').startswith('no-store'),'Captcha must not be cached')
    captcha=json.loads(body)
    image=base64.b64decode(captcha['image'].split(',',1)[1])
    require(image.startswith(b'\x89PNG\r\n\x1a\n') and len(image)>100,'Captcha must be PNG')
    for _ in range(9):
        status,_,_=browser.request('/login/captcha',dict(purpose='SEND',_csrf=csrf),headers={'Origin':args.base})
        require(status==200,'First ten captcha requests must succeed')
    status,_,_=browser.request('/login/captcha',dict(purpose='SEND',_csrf=csrf),headers={'Origin':args.base})
    require(status==429,'Eleventh captcha request must be limited')
    initial=outbox.stat().st_size
    status,headers,body=browser.request(path,send,headers={'Origin':args.base})
    require(status==200 and headers.get('Cache-Control','').startswith('no-store'),'SMS send must succeed without caching')
    result=json.loads(body)
    challenge=result.get('result',result)['challengeId']
    require(re.fullmatch('[A-Za-z0-9_-]{22}',challenge),'Challenge ID must be random and bounded')
    require(outbox.stat().st_size>initial,'Mock sender must receive one code')
    last=outbox.read_text().splitlines()[-1].split(' ')
    require(len(last)==3 and last[0]==challenge and last[1]==phone and re.fullmatch('[0-9]{6}',last[2]),
            'Protected outbox must match the new challenge')
    form=dict(phone=phone,challengeId=challenge,code=last[2],_csrf=csrf)
    status,_,_=browser.request('/login/sms/authenticate',form)
    require(status==403,'SMS authentication without Origin must be rejected')
    status,headers,_=browser.request('/login/sms/authenticate',form,headers={'Origin':args.base})
    require(status==302 and '/oauth2/authorize' in headers.get('Location',''),
            'SMS login must resume the saved authorization')
    after=next(cookie.value for cookie in browser.cookies if cookie.name=='__Host-mars-session')
    require(before!=after,'SMS login must replace the session ID')
    _,stale=authorization(Browser(args.base,args.ca,old_cookies),'test-browser',callback,prompt='none')
    require(stale.get('error')==['login_required'],'Old session must remain unauthenticated')
    verifier2,response=authorization(browser,'test-browser',callback)
    require('code' in response,'SMS login must issue an authorization code')
    status,token=exchange(browser,'test-browser',callback,response['code'][0],verifier2)
    require(status==200 and 'access_token' in token,'SMS login authorization code must be exchangeable')
    access=claims(token['access_token'])
    status,_,me=browser.request('/auth/v1/me',headers={'Authorization':'Bearer '+token['access_token']})
    require(status==200 and access['sub'] in me.decode(),'SMS token must identify the account')
    if args.audit_output: protected(args.audit_output,access['sub']+'\n')
    second=Browser(args.base,args.ca)
    status,_,page=second.request('/login')
    require(status==200,'Second unauthenticated session must reach login')
    second_csrf=re.search(rb'name="_csrf"[^>]*value="([^"]+)"',page).group(1).decode()
    second_phone='+1555'+''.join(str(secrets.randbelow(10)) for _ in range(7))
    status,_,body=second.request(path,dict(phone=second_phone,_csrf=second_csrf),headers={'Origin':args.base})
    require(status==200,'Second SMS challenge must be issued')
    second_id=json.loads(body).get('result',json.loads(body))['challengeId']
    second_code=outbox.read_text().splitlines()[-1].split(' ')[2]
    wrong='000000' if second_code!='000000' else '999999'
    second_form=dict(phone=second_phone,challengeId=second_id,code=wrong,_csrf=second_csrf)
    for _ in range(3):
        status,_,_=second.request('/login/sms/authenticate',second_form,headers={'Origin':args.base})
        require(status==400,'Wrong SMS code must be rejected')
    second_form['code']=second_code
    status,_,_=second.request('/login/sms/authenticate',second_form,headers={'Origin':args.base})
    require(status==428,'Third wrong code must require image challenge on the next attempt')
    print('PASS: SMS login, CSRF and origin rejection, protected mock, PNG and captcha limits, error threshold, session replacement, authorization code and Bearer identity')


if __name__=='__main__':
    main()
