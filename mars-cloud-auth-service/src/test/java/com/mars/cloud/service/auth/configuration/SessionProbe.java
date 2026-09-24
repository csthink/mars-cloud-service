package com.mars.cloud.service.auth.configuration;

import java.time.Duration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;

/** Read-only check of the authenticated session stored by the real application. */
public final class SessionProbe {
    public static void main(String[] args) {
        var config=new RedisStandaloneConfiguration(System.getenv("SPRING_DATA_REDIS_HOST"),Integer.parseInt(System.getenv("SPRING_DATA_REDIS_PORT")));
        config.setDatabase(Integer.parseInt(System.getenv("SPRING_DATA_REDIS_DATABASE")));
        config.setPassword(RedisPassword.of(System.getenv("SPRING_DATA_REDIS_PASSWORD")));
        var factory=new LettuceConnectionFactory(config);
        try {
            factory.afterPropertiesSet();factory.start();
            var template=new RedisTemplate<String,Object>();template.setConnectionFactory(factory);
            template.setKeySerializer(new StringRedisSerializer());template.setHashKeySerializer(new StringRedisSerializer());
            template.setDefaultSerializer(new AuthSessionConfiguration().springSessionDefaultRedisSerializer());template.afterPropertiesSet();
            var repository=new RedisIndexedSessionRepository(template);repository.setRedisKeyNamespace("mars:auth:session");
            String user=System.getenv("MARS_AUTH_LOCAL_LOGIN_USER_ID");
            var sessions=repository.findByPrincipalName(user);
            if(sessions.isEmpty())throw new AssertionError("Principal index must find an authenticated session");
            for(var session:sessions.values()) {
                if(!session.getMaxInactiveInterval().equals(Duration.ofDays(30)))throw new AssertionError("Session idle lifetime must be 30 days");
                SecurityContext context=session.getAttribute("SPRING_SECURITY_CONTEXT");
                if(context==null||!user.equals(context.getAuthentication().getName())||context.getAuthentication().getCredentials()!=null)
                    throw new AssertionError("Persisted session must contain stable identity and no credentials");
            }
            System.out.println("PASS: Redis principal index, 30-day sliding lifetime, safe security-context serialization");
        } finally {factory.destroy();}
    }
}
