package com.mars.cloud.service.auth.infrastructure.authorization;

import com.mars.cloud.service.auth.application.AuthSessionService;
import com.mars.cloud.service.auth.domain.ClientPolicy;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Persists the session association with the protocol authorization before token exchange. */
public final class SessionAuthorizationService implements OAuth2AuthorizationService {
    public static final String SID="mars.sid";
    private final OAuth2AuthorizationService delegate;
    private final AuthSessionService sessions;
    private final org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository clients;
    public SessionAuthorizationService(OAuth2AuthorizationService delegate,AuthSessionService sessions,org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository clients) {
        this.delegate=delegate; this.sessions=sessions;this.clients=clients;
    }
    @Override public void save(OAuth2Authorization authorization) {
        if (authorization.getAttribute(SID)==null && authorization.getToken(org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode.class)!=null) {
            var attributes=RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof ServletRequestAttributes servlet)) throw new IllegalStateException("Authorization requires a browser request");
            String id=clients.findById(authorization.getRegisteredClientId()).getClientId();
            String sid=sessions.authorizationSession(authorization.getPrincipalName(),id,ClientPolicy.nativeClient(id),servlet.getRequest());
            authorization=OAuth2Authorization.from(authorization).attribute(SID,sid).build();
        }
        delegate.save(authorization);
    }
    @Override public void remove(OAuth2Authorization authorization) { delegate.remove(authorization); }
    @Override public OAuth2Authorization findById(String id) { return delegate.findById(id); }
    @Override public OAuth2Authorization findByToken(String token,OAuth2TokenType type) { return delegate.findByToken(token,type); }
}
