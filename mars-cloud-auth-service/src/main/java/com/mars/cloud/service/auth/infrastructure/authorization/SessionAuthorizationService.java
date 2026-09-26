package com.mars.cloud.service.auth.infrastructure.authorization;

import com.mars.cloud.service.auth.application.AuthSessionService;
import com.mars.cloud.service.auth.domain.ClientPolicy;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Persists the session association with the protocol authorization before token exchange, and records native device
 * activity whenever an authorization that already carries a session identifier saves tokens (code exchange or refresh).
 */
public final class SessionAuthorizationService implements OAuth2AuthorizationService {
    public static final String SID="mars.sid";
    private final OAuth2AuthorizationService delegate;
    private final AuthSessionService sessions;
    private final RegisteredClientRepository clients;
    public SessionAuthorizationService(OAuth2AuthorizationService delegate,AuthSessionService sessions,RegisteredClientRepository clients) {
        this.delegate=delegate; this.sessions=sessions;this.clients=clients;
    }
    @Override public void save(OAuth2Authorization authorization) {
        String existing=authorization.getAttribute(SID);
        if (existing==null && authorization.getToken(OAuth2AuthorizationCode.class)!=null) {
            var attributes=RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof ServletRequestAttributes servlet)) throw new IllegalStateException("Authorization requires a browser request");
            String id=clientId(authorization);
            String sid=sessions.authorizationSession(authorization.getPrincipalName(),id,ClientPolicy.nativeClient(id),authorization.getId(),servlet.getRequest());
            authorization=OAuth2Authorization.from(authorization).attribute(SID,sid).build();
        } else if (existing!=null && authorization.getAccessToken()!=null && ClientPolicy.nativeClient(clientId(authorization))) {
            sessions.touchNative(existing);
        }
        delegate.save(authorization);
    }
    private String clientId(OAuth2Authorization authorization) {
        RegisteredClient client=clients.findById(authorization.getRegisteredClientId());
        if (client==null) throw new IllegalStateException("Authorization refers to an unknown client");
        return client.getClientId();
    }
    @Override public void remove(OAuth2Authorization authorization) { delegate.remove(authorization); }
    @Override public OAuth2Authorization findById(String id) { return delegate.findById(id); }
    @Override public OAuth2Authorization findByToken(String token,OAuth2TokenType type) { return delegate.findByToken(token,type); }
}
