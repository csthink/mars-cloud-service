package com.mars.cloud.service.auth.infrastructure.authorization;

import com.mars.cloud.service.auth.application.AuthSessionService;
import com.mars.cloud.service.auth.domain.ClientPolicy;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Persists the session association with the protocol authorization before token exchange, and records native device
 * activity whenever an authorization that already carries a session identifier saves a live access token (code exchange
 * or refresh). Registration and the authorization row commit together, so a concurrent admission of the same account
 * never sees the device row without its authorization record.
 */
public final class SessionAuthorizationService implements OAuth2AuthorizationService {
    public static final String SID="mars.sid";
    private final OAuth2AuthorizationService delegate;
    private final AuthSessionService sessions;
    private final RegisteredClientRepository clients;
    private final TransactionTemplate transactions;
    public SessionAuthorizationService(OAuth2AuthorizationService delegate,AuthSessionService sessions,RegisteredClientRepository clients,PlatformTransactionManager manager) {
        this.delegate=delegate; this.sessions=sessions;this.clients=clients;
        this.transactions=new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Override public void save(OAuth2Authorization authorization) {
        String existing=authorization.getAttribute(SID);
        if (existing==null && authorization.getToken(OAuth2AuthorizationCode.class)!=null) {
            var attributes=RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof ServletRequestAttributes servlet)) throw new IllegalStateException("Authorization requires a browser request");
            String id=clientId(authorization);
            OAuth2Authorization pending=authorization;
            transactions.executeWithoutResult(status -> {
                String sid=sessions.authorizationSession(pending.getPrincipalName(),id,ClientPolicy.nativeClient(id),pending.getId(),servlet.getRequest());
                delegate.save(OAuth2Authorization.from(pending).attribute(SID,sid).build());
            });
            return;
        }
        if (existing!=null && authorization.getAccessToken()!=null && authorization.getAccessToken().isActive() && ClientPolicy.nativeClient(clientId(authorization))) {
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
