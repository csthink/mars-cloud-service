package com.mars.cloud.service.auth.configuration;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.mars.cloud.service.auth.infrastructure.account.AuthAccountMapper;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
public class LocalLoginConfiguration {
    @Bean @DependsOnDatabaseInitialization
    AuthenticationProvider localLoginProvider(AuthProperties properties,AuthEnvironment environment,AuthAccountMapper accounts,
            JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock) {
        if (!properties.getLocalLogin().isEnabled()) return new AuthenticationProvider() {
            @Override public Authentication authenticate(Authentication value) { throw new BadCredentialsException("Login unavailable"); }
            @Override public boolean supports(Class<?> type) { return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type); }
        };
        var encoder=new BCryptPasswordEncoder();
        var login=properties.getLocalLogin(); long id=Long.parseLong(login.getUserId());
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            if (accounts.activeUser(id)==null) {
                Timestamp now=Timestamp.from(clock.instant());
                jdbc.update("INSERT INTO sys_user (user_id,status,created_at,updated_at) VALUES (?,'ACTIVE',?,?)",id,now,now);
                jdbc.update("INSERT INTO sys_user_credential (credential_id,user_id,type,secret_hash) VALUES (?,?,'LOCAL_TEST',?)",IdWorker.getId(),id,encoder.encode(login.getPassword()));
            } else if (accounts.localPassword(id)==null || !encoder.matches(login.getPassword(),accounts.localPassword(id)))
                throw new IllegalStateException("Existing local credentials do not match the protected input");
        });
        var provider=new DaoAuthenticationProvider(username -> {
            if (!login.getUserId().equals(username) || accounts.activeUser(id)==null)
                throw new UsernameNotFoundException("Account unavailable");
            return User.withUsername(username).password(accounts.localPassword(id)).authorities("LOCAL_LOGIN").build();
        });
        provider.setPasswordEncoder(encoder); return provider;
    }
    @Bean AuthenticationManager localLoginManager(AuthenticationProvider localLoginProvider) {
        return new ProviderManager(localLoginProvider);
    }
}
