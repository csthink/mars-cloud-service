package com.mars.cloud.service.auth.application;

import com.mars.cloud.service.auth.domain.ClientPolicy;
import com.mars.cloud.service.auth.infrastructure.authorization.SessionAuthorizationService;
import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

public final class AuthTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {
    private final SigningKeyService keys;
    public AuthTokenCustomizer(SigningKeyService keys) { this.keys=keys; }
    @Override public void customize(JwtEncodingContext context) {
        String sid=context.getAuthorization()==null?null:context.getAuthorization().getAttribute(SessionAuthorizationService.SID);
        if (sid==null || sid.isBlank()) throw new OAuth2AuthenticationException(OAuth2ErrorCodes.SERVER_ERROR);
        context.getJwsHeader().keyId(keys.kid());
        String id=context.getRegisteredClient().getClientId();
        if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            context.getClaims().claims(claims -> claims.keySet().retainAll(Set.of("iss","sub","aud","exp","iat","jti","scope")));
            context.getClaims().audience(ClientPolicy.audiences(id)).claim("sid",sid).claim("client_id",id).claim("tenant_id","default");
        } else {
            context.getClaims().claims(claims -> claims.keySet().retainAll(Set.of("iss","sub","aud","exp","iat","jti","nonce","auth_time","azp","at_hash","c_hash","sid")));
            // Preserve the protocol session hash used by standard RP-Initiated Logout.
        }
    }
}
