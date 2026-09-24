package com.mars.cloud.service.auth.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods=false)
@EnableRedisIndexedHttpSession(maxInactiveIntervalInSeconds=2592000,redisNamespace="mars:auth:session")
public class AuthSessionConfiguration {
    @Bean RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        var mapper=JsonMapper.builder().addModules(SecurityJacksonModules.getModules(getClass().getClassLoader(), tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator.builder().allowIfSubType(Long.class).allowIfSubType(Integer.class))).build();
        return new JacksonJsonRedisSerializer<>(mapper,Object.class);
    }
    @Bean CookieSerializer cookieSerializer() {
        var serializer=new DefaultCookieSerializer();
        serializer.setCookieName("__Host-mars-session"); serializer.setCookiePath("/");
        serializer.setUseSecureCookie(true); serializer.setUseHttpOnlyCookie(true); serializer.setSameSite("Lax");
        return serializer;
    }
    @Bean <S extends Session> SessionRegistry sessionRegistry(FindByIndexNameSessionRepository<S> repository) {
        return new SpringSessionBackedSessionRegistry<>(repository);
    }
}
