package com.mars.cloud.service.auth.interfaces;

import com.mars.cloud.service.auth.application.SmsLoginService;
import com.mars.cloud.mvc.annotation.IgnoreResponseAnnotation;
import com.mars.cloud.service.auth.configuration.AuthEnvironment;
import com.mars.cloud.service.auth.configuration.AuthProperties;
import com.mars.cloud.service.auth.infrastructure.sms.SmsFailure;
import com.mars.cloud.mvc.exception.HttpException;
import com.mars.cloud.common.response.UnifyResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class SmsLoginController {
    private final SmsLoginService sms;
    private final SmsOrigin origin;
    private final AuthEnvironment environment;
    private final AuthProperties properties;
    public SmsLoginController(SmsLoginService sms,SmsOrigin origin,AuthEnvironment environment,AuthProperties properties) {
        this.sms=sms; this.origin=origin; this.environment=environment; this.properties=properties;
    }
    @IgnoreResponseAnnotation
    @ExceptionHandler(HttpException.class)
    public ResponseEntity<UnifyResponse<Void>> smsFailure(HttpException failure) {
        return ResponseEntity.status(failure.getHttpStatusCode()).cacheControl(CacheControl.noStore())
                .body(UnifyResponse.fail(failure.getErrCode(),failure.getErrorMsg()));
    }
    @PostMapping("/login/sms/send")
    public ResponseEntity<Map<String,String>> send(@RequestParam String phone,
            @RequestParam(required=false) String captchaId,@RequestParam(required=false) String captchaAnswer,
            HttpServletRequest request) {
        origin.require(request);
        String id=sms.send(phone,captchaId,captchaAnswer,request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("challengeId",id));
    }
    @IgnoreResponseAnnotation
    @PostMapping("/login/captcha")
    public ResponseEntity<Map<String,String>> captcha(@RequestParam String purpose,HttpServletRequest request) {
        origin.require(request);
        var image=sms.captcha(purpose,request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("challengeId",image.id(),
                "image","data:image/png;base64,"+Base64.getEncoder().encodeToString(image.png())));
    }
    @IgnoreResponseAnnotation
    @GetMapping(value="/login",produces="text/html")
    public ResponseEntity<String> login(HttpServletRequest request) {
        if (!environment.local()) throw SmsFailure.unavailable();
        CsrfToken csrf=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
        if (csrf==null) throw SmsFailure.unavailable();
        String token=csrf.getToken();
        String local=properties.getLocalLogin().isEnabled() ? """
            <fieldset><legend>Local test password</legend>
            <form action="/login" method="post">
            <label>User ID <input name="username" autocomplete="username"></label>
            <label>Password <input name="password" type="password" autocomplete="current-password"></label>
            <input type="hidden" name="_csrf" value="%s">
            <button type="submit">Sign in</button></form></fieldset>
            """.formatted(token) : "";
        String html="""
            <!doctype html><html lang="en"><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Local authentication verification</title>
            <style>body{font:16px system-ui;max-width:36rem;margin:3rem auto;padding:1rem}
            fieldset{margin:1rem 0;padding:1rem;border:1px solid #aaa;border-radius:.5rem}
            label{display:block;margin:.75rem 0}input{font:inherit;max-width:100%%;padding:.35rem}
            button{font:inherit;padding:.5rem 1rem}#image{display:block;max-width:100%%}</style>
            <h1>Local authentication verification</h1>
            <fieldset><legend>SMS</legend>
            <label>Phone <input id="phone" autocomplete="tel" placeholder="+15551234567"></label>
            <button id="send" type="button">Send code</button>
            <p id="notice" role="status"></p>
            <button id="captcha" type="button">Image challenge</button><img id="image" alt="">
            <label>Image answer <input id="captchaAnswer" maxlength="5"></label>
            <form action="/login/sms/authenticate" method="post" id="smsForm">
            <input type="hidden" name="_csrf" value="%s">
            <input type="hidden" name="phone" id="formPhone">
            <input type="hidden" name="challengeId" id="challengeId">
            <input type="hidden" name="captchaId" id="captchaId">
            <input type="hidden" name="captchaAnswer" id="formCaptchaAnswer">
            <label>SMS code <input name="code" inputmode="numeric" maxlength="6" autocomplete="one-time-code"></label>
            <button type="submit">Sign in with SMS</button></form></fieldset>
            %s
            <script>
            const token=document.querySelector('#smsForm [name=_csrf]').value;
            let purpose='SEND';
            async function post(path,data){
              const body=new URLSearchParams({...data,_csrf:token});
              const response=await fetch(path,{method:'POST',body,headers:{'Content-Type':'application/x-www-form-urlencoded'}});
              const value=await response.json();
              if(!response.ok)throw new Error(value.message||'Request failed');
              return value.result||value;
            }
            document.querySelector('#captcha').onclick=async()=>{
              try{const value=await post('/login/captcha',{purpose});
                document.querySelector('#image').src=value.image;
                document.querySelector('#captchaId').value=value.challengeId;
              }catch(error){document.querySelector('#notice').textContent=error.message}
            };
            document.querySelector('#send').onclick=async()=>{
              try{const phone=document.querySelector('#phone').value;
                const value=await post('/login/sms/send',{phone,captchaId:document.querySelector('#captchaId').value,
                  captchaAnswer:document.querySelector('#captchaAnswer').value});
                document.querySelector('#formPhone').value=phone;
                document.querySelector('#challengeId').value=value.challengeId;
                document.querySelector('#notice').textContent='Code sent';
                purpose='VERIFY';
              }catch(error){document.querySelector('#notice').textContent=error.message}
            };
            document.querySelector('#smsForm').onsubmit=()=>{
              document.querySelector('#formCaptchaAnswer').value=document.querySelector('#captchaAnswer').value;
            };
            </script></html>
            """.formatted(token,local);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(html);
    }
}
