package com.mars.cloud.service.auth.infrastructure.authorization;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Serializes code validation and token persistence on the authorization row across processes. */
public final class AtomicCodeAuthenticationProvider implements AuthenticationProvider {
    private final AuthenticationProvider delegate;
    private final OAuth2AuthorizationService authorizations;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public AtomicCodeAuthenticationProvider(AuthenticationProvider delegate,OAuth2AuthorizationService authorizations,
            JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.delegate=delegate; this.authorizations=authorizations; this.jdbc=jdbc;
        transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Override public Authentication authenticate(Authentication authentication) {
        var token=(OAuth2AuthorizationCodeAuthenticationToken)authentication;
        OAuth2Authorization found=authorizations.findByToken(token.getCode(),new OAuth2TokenType("code"));
        if (found==null) return delegate.authenticate(authentication);
        var result = transaction.execute(status -> {
            jdbc.queryForObject("SELECT id FROM oauth2_authorization WHERE id=? FOR UPDATE",String.class,found.getId());
            try { return new Outcome(delegate.authenticate(authentication),null); }
            catch (org.springframework.security.oauth2.core.OAuth2AuthenticationException exception) {
                // Standard protocol rejection may revoke an already issued token. Commit that revocation.
                return new Outcome(null,exception);
            }
        });
        if (result == null) throw new IllegalStateException("Code transaction returned no outcome");
        if (result.failure() != null) throw result.failure();
        return result.authentication();
    }
    private record Outcome(Authentication authentication,org.springframework.security.oauth2.core.OAuth2AuthenticationException failure) { }
    @Override public boolean supports(Class<?> type) { return delegate.supports(type); }
}
