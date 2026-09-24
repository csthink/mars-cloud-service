package com.mars.cloud.service.auth.application;

import com.mars.cloud.service.auth.infrastructure.sms.CaptchaProvider;
import com.mars.cloud.service.auth.infrastructure.sms.SmsChallengeStore;
import com.mars.cloud.service.auth.infrastructure.sms.SmsCrypto;
import com.mars.cloud.service.auth.infrastructure.sms.SmsFailure;
import com.mars.cloud.service.auth.infrastructure.sms.SmsRiskService;
import com.mars.cloud.service.auth.infrastructure.sms.SmsSender;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public final class SmsLoginService {
    private final SmsCrypto crypto;
    private final SmsSender sender;
    private final SmsRiskService risk;
    private final SmsChallengeStore challenges;
    private final CaptchaProvider captchas;
    private final AccountService accounts;
    public SmsLoginService(SmsCrypto crypto,SmsSender sender,SmsRiskService risk,SmsChallengeStore challenges,
            CaptchaProvider captchas,AccountService accounts) {
        this.crypto=crypto; this.sender=sender; this.risk=risk; this.challenges=challenges;
        this.captchas=captchas; this.accounts=accounts;
    }
    public String send(String phone,String captchaId,String captchaAnswer,HttpServletRequest request) {
        requireAvailable();
        phone=canonical(phone);
        String session=session(request);
        try {
            SmsRiskService.Reservation reservation=risk.reserve(phone,request.getRemoteAddr(),false);
            if (reservation==SmsRiskService.Reservation.CAPTCHA_REQUIRED) {
                if (captchaId==null || captchaId.isBlank()) throw SmsFailure.captchaRequired();
                if (!captchas.consume("SEND",session,captchaId,captchaAnswer)) throw SmsFailure.captchaInvalid();
                reservation=risk.reserve(phone,request.getRemoteAddr(),true);
            }
            if (reservation==SmsRiskService.Reservation.LIMITED) throw SmsFailure.limited();
            if (reservation==SmsRiskService.Reservation.PAUSED) throw SmsFailure.unavailable();
            if (reservation!=SmsRiskService.Reservation.OK) throw SmsFailure.unavailable();
            String id=SmsCrypto.randomId(), code=SmsCrypto.code();
            sender.send(phone,id,code);
            challenges.put(phone,"LOGIN",session,id,code);
            return id;
        } catch (DataAccessException ex) { throw SmsFailure.unavailable(); }
    }
    public String authenticate(String phone,String id,String code,String captchaId,String captchaAnswer, String session) {
        requireAvailable();
        phone=canonical(phone);
        if (id==null || !id.matches("[A-Za-z0-9_-]{22}") || code==null || !code.matches("[0-9]{6}"))
            throw SmsFailure.invalid();
        try {
            if (risk.verificationCaptchaRequired(phone,session)) {
                if (captchaId==null || captchaId.isBlank()) throw SmsFailure.captchaRequired();
                if (!captchas.consume("VERIFY",session,captchaId,captchaAnswer)) throw SmsFailure.captchaInvalid();
            }
            int result=challenges.verify(phone,"LOGIN",session,id,code);
            if (result!=1) {
                if (result==2) risk.wrongCode(phone,session);
                throw SmsFailure.invalid();
            }
            return Long.toString(accounts.registerVerifiedPhone(phone));
        } catch (DataAccessException ex) { throw SmsFailure.unavailable(); }
    }
    public CaptchaProvider.Image captcha(String purpose,HttpServletRequest request) {
        requireAvailable();
        if (!"SEND".equals(purpose) && !"VERIFY".equals(purpose)) throw SmsFailure.captchaInvalid();
        try { return captchas.create(purpose,session(request),request.getRemoteAddr()); }
        catch (DataAccessException ex) { throw SmsFailure.unavailable(); }
    }
    private void requireAvailable() {
        if (!crypto.enabled() || !sender.available()) throw SmsFailure.unavailable();
    }
    private static String canonical(String phone) {
        try { return AccountService.canonicalPhone(phone); }
        catch (IllegalArgumentException ex) { throw SmsFailure.invalid(); }
    }
    private static String session(HttpServletRequest request) {
        HttpSession session=request.getSession(false);
        if (session==null) throw SmsFailure.invalid();
        return session.getId();
    }
}
