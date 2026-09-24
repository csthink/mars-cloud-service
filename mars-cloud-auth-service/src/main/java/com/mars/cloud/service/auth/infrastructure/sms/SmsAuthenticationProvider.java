package com.mars.cloud.service.auth.infrastructure.sms;

import com.mars.cloud.service.auth.application.SmsLoginService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;

public final class SmsAuthenticationProvider implements AuthenticationProvider {
    private final SmsLoginService sms;
    public SmsAuthenticationProvider(SmsLoginService sms) { this.sms=sms; }
    @Override public Authentication authenticate(Authentication authentication) {
        SmsAuthenticationToken token=(SmsAuthenticationToken)authentication;
        try {
            String userId=sms.authenticate(token.phone(),token.challengeId(),token.code(),token.captchaId(),
                    token.captchaAnswer(),token.session());
            return UsernamePasswordAuthenticationToken.authenticated(userId,null,
                    List.of(FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.OTT_AUTHORITY),
                            new SimpleGrantedAuthority("SMS_LOGIN")));
        } catch (SmsFailure failure) {
            throw new SmsAuthenticationException(failure.getHttpStatusCode(),failure.getErrCode());
        } catch (RuntimeException failure) {
            throw new SmsAuthenticationException(503,64006);
        }
    }
    @Override public boolean supports(Class<?> type) { return SmsAuthenticationToken.class.isAssignableFrom(type); }
}
