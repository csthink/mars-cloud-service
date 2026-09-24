package com.mars.cloud.service.auth.infrastructure.authorization;

import com.mars.cloud.service.auth.domain.ClientPolicy;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/** Native applications must receive an explicit decision for every authorization request. */
public final class ClientConsentService implements OAuth2AuthorizationConsentService {
    private final OAuth2AuthorizationConsentService delegate;
    private final RegisteredClientRepository clients;
    public ClientConsentService(OAuth2AuthorizationConsentService delegate,RegisteredClientRepository clients) { this.delegate=delegate;this.clients=clients; }
    private boolean nativeClient(String id) {
        var client=clients.findById(id);
        if (client==null) throw new IllegalStateException("Client registration is unavailable");
        return ClientPolicy.nativeClient(client.getClientId());
    }
    @Override public void save(OAuth2AuthorizationConsent consent) { if (!nativeClient(consent.getRegisteredClientId())) delegate.save(consent); }
    @Override public OAuth2AuthorizationConsent findById(String id,String principal) { return nativeClient(id)?null:delegate.findById(id,principal); }
    @Override public void remove(OAuth2AuthorizationConsent consent) { delegate.remove(consent); }
}
