package com.mars.cloud.service.auth.configuration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import static org.assertj.core.api.Assertions.*;

class SessionSerializationTest {
    private final org.springframework.data.redis.serializer.RedisSerializer<Object> serializer=
            new AuthSessionConfiguration().springSessionDefaultRedisSerializer();
    @Test void numericSessionMetadataAndSecurityContextRoundTrip() {
        for (Object value:List.of(2592000,1234567890123L,"session-value"))
            assertThat(serializer.deserialize(serializer.serialize(value))).isEqualTo(value);
        var user=User.withUsername("10001").password("unused").authorities("LOCAL_LOGIN").build();
        var context=new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(user,null,user.getAuthorities()));
        var restored=(SecurityContextImpl)serializer.deserialize(serializer.serialize(context));
        assertThat(restored.getAuthentication().getName()).isEqualTo("10001");
        assertThat(restored.getAuthentication().getCredentials()).isNull();
    }
    @Test void savedAuthorizationRequestAndCsrfTokenRoundTrip() {
        var request=new MockHttpServletRequest("GET","/oauth2/authorize");
        request.addParameter("client_id","test-browser");request.addParameter("scope","openid");
        var saved=new DefaultSavedRequest(request,null);
        var restored=(DefaultSavedRequest)serializer.deserialize(serializer.serialize(saved));
        assertThat(restored.getParameterValues("client_id")).containsExactly("test-browser");
        var csrf=new DefaultCsrfToken("X-CSRF-TOKEN","_csrf","test-challenge");
        assertThat(((DefaultCsrfToken)serializer.deserialize(serializer.serialize(csrf))).getToken()).isEqualTo("test-challenge");
    }
    @Test void arbitraryJavaTypesAreRejected() {
        assertThatThrownBy(() -> serializer.deserialize("[\"java.io.File\",\"/unused\"]".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(org.springframework.data.redis.serializer.SerializationException.class);
    }
}
