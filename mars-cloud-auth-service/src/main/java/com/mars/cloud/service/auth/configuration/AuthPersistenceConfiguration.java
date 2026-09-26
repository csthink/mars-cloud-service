package com.mars.cloud.service.auth.configuration;

import com.mars.cloud.service.auth.application.AuthSessionService;
import com.mars.cloud.service.auth.application.DeviceSessionService;
import com.mars.cloud.service.auth.infrastructure.session.RevocationStore;
import com.mars.cloud.service.auth.infrastructure.authorization.SessionAuthorizationService;
import com.mars.cloud.service.auth.infrastructure.client.SysClientRegisteredClientRepository;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
public class AuthPersistenceConfiguration {
    @Bean Clock authClock() { return Clock.systemUTC(); }
    @Bean @DependsOnDatabaseInitialization
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbc,AuthEnvironment environment) {
        Integer formal=jdbc.queryForObject("SELECT COUNT(*) FROM sys_client WHERE test_client=false",Integer.class);
        if (formal==null || formal!=6) throw new IllegalStateException("The six formal clients must be registered");
        if (!environment.local() && (jdbc.queryForObject("SELECT COUNT(*) FROM sys_client WHERE test_client=true",Integer.class)!=0
                || jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_credential WHERE type='LOCAL_TEST'",Integer.class)!=0))
            throw new IllegalStateException("Test data is forbidden outside local/test");
        var repository=new SysClientRegisteredClientRepository(jdbc,environment.local());
        for (String id:jdbc.queryForList("SELECT client_id FROM sys_client",String.class)) {
            if (repository.findByClientId(id)==null) throw new IllegalStateException("Client registration is unavailable");
        }
        return repository;
    }
    /** Looks the authorization service up lazily: it decorates AuthSessionService, which itself needs this bean. */
    @Bean DeviceSessionService deviceSessionService(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,
            SessionRepository<? extends Session> sessions,ObjectProvider<OAuth2AuthorizationService> authorizations,RevocationStore revocations) {
        return new DeviceSessionService(jdbc,manager,clock,sessions,authorizations::getObject,revocations);
    }
    @Bean OAuth2AuthorizationService authorizationService(JdbcTemplate jdbc,RegisteredClientRepository clients,AuthSessionService sessions,PlatformTransactionManager manager) {
        return new SessionAuthorizationService(new JdbcOAuth2AuthorizationService(jdbc,clients),sessions,clients,manager);
    }
    @Bean OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate jdbc,RegisteredClientRepository clients) {
        return new com.mars.cloud.service.auth.infrastructure.authorization.ClientConsentService(new JdbcOAuth2AuthorizationConsentService(jdbc,clients),clients);
    }
}
