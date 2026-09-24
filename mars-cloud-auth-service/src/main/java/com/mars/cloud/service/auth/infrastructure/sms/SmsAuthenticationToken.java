package com.mars.cloud.service.auth.infrastructure.sms;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import java.util.List;

public final class SmsAuthenticationToken extends AbstractAuthenticationToken {
    private final String phone;
    private final String challengeId;
    private String code;
    private final String captchaId;
    private String captchaAnswer;
    private final String session;
    public SmsAuthenticationToken(String phone,String challengeId,String code,String captchaId,String captchaAnswer,String session) {
        super(List.of());
        this.phone=phone; this.challengeId=challengeId; this.code=code; this.captchaId=captchaId;
        this.captchaAnswer=captchaAnswer; this.session=session; setAuthenticated(false);
    }
    public String phone() { return phone; }
    public String challengeId() { return challengeId; }
    public String code() { return code; }
    public String captchaId() { return captchaId; }
    public String captchaAnswer() { return captchaAnswer; }
    public String session() { return session; }
    @Override public Object getPrincipal() { return ""; }
    @Override public Object getCredentials() { return code; }
    @Override public void eraseCredentials() { code=null; captchaAnswer=null; }
}
