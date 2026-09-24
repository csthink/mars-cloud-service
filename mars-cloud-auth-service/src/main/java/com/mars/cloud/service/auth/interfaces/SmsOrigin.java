package com.mars.cloud.service.auth.interfaces;

import com.mars.cloud.mvc.exception.HttpException;
import com.mars.cloud.service.auth.configuration.AuthProperties;
import com.mars.cloud.service.auth.infrastructure.sms.SmsFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public final class SmsOrigin {
    private final String issuer;
    public SmsOrigin(AuthProperties properties) { issuer=properties.getIssuer(); }
    public void require(HttpServletRequest request) {
        String origin=request.getHeader("Origin"), site=request.getHeader("Sec-Fetch-Site");
        if (!issuer.equals(origin) || "cross-site".equalsIgnoreCase(site) || "same-site".equalsIgnoreCase(site))
            throw new HttpException(403,AuthErrorCode.SMS_CHALLENGE_INVALID);
        if (request.getSession(false)==null) throw SmsFailure.invalid();
    }
}
