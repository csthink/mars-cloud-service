package com.mars.cloud.service.auth.infrastructure.sms;

import com.mars.cloud.service.auth.application.AuthSessionService;
import com.mars.cloud.service.auth.application.SmsLoginService;
import com.mars.cloud.service.auth.interfaces.SmsOrigin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

public final class SmsAuthenticationFilter extends AbstractAuthenticationProcessingFilter {
    private final SmsOrigin origin;
    public SmsAuthenticationFilter(SmsLoginService sms,SmsOrigin origin,AuthSessionService sessions,CsrfTokenRepository csrf) {
        super(PathPatternRequestMatcher.withDefaults().matcher(org.springframework.http.HttpMethod.POST,"/login/sms/authenticate"),
                new ProviderManager(new SmsAuthenticationProvider(sms)));
        this.origin=origin;
        setSessionAuthenticationStrategy(new CompositeSessionAuthenticationStrategy(java.util.List.of(
                new ChangeSessionIdAuthenticationStrategy(),new CsrfAuthenticationStrategy(csrf))));
        setSecurityContextRepository(new HttpSessionSecurityContextRepository());
        var redirect=new SavedRequestAwareAuthenticationSuccessHandler();
        setAuthenticationSuccessHandler((request,response,authentication) -> {
            try {
                sessions.login(authentication.getName(),request);
                redirect.onAuthenticationSuccess(request,response,authentication);
            } catch (RuntimeException exception) {
                SecurityContextHolder.clearContext();
                var session=request.getSession(false);
                if (session!=null) session.invalidate();
                failure(response,503,64006,"SMS is unavailable");
            }
        });
        setAuthenticationFailureHandler((request,response,exception) -> {
            if (exception instanceof SmsAuthenticationException smsFailure) {
                if (smsFailure.status()==503) {
                    var session=request.getSession(false);
                    if (session!=null) session.invalidate();
                }
                failure(response,smsFailure.status(),smsFailure.code(),message(smsFailure.code()));
            }
            else failure(response,400,64002,"SMS challenge is invalid");
        });
    }
    @Override public Authentication attemptAuthentication(HttpServletRequest request,HttpServletResponse response) {
        try { origin.require(request); }
        catch (RuntimeException ex) { throw new SmsAuthenticationException(403,64002); }
        if (request.getSession(false)==null) throw new SmsAuthenticationException(400,64002);
        SmsAuthenticationToken token=new SmsAuthenticationToken(
                request.getParameter("phone"),request.getParameter("challengeId"),request.getParameter("code"),
                request.getParameter("captchaId"),request.getParameter("captchaAnswer"),request.getSession(false).getId());
        return getAuthenticationManager().authenticate(token);
    }
    private static String message(int code) {
        return switch(code) {
            case 64003 -> "Image challenge is required";
            case 64004 -> "Image challenge is invalid";
            case 64005 -> "SMS send limit reached";
            case 64006 -> "SMS is unavailable";
            default -> "SMS challenge is invalid";
        };
    }
    private static void failure(HttpServletResponse response,int status,int code,String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.setHeader("Cache-Control","no-store");
        response.getWriter().write("{\"success\":false,\"code\":\""+code+"\",\"message\":\""+message+"\"}");
    }
}
