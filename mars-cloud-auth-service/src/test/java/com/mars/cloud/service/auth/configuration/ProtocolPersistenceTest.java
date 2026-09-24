package com.mars.cloud.service.auth.configuration;

import com.mars.cloud.service.auth.domain.ClientPolicy;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import static org.assertj.core.api.Assertions.*;

class ProtocolPersistenceTest {
    @Test void identityClaimsRemainReadableWithTheStandardJdbcMapper() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql")).execute(source);
        var client=ClientPolicy.create("portal",List.of("https://portal.example/cb"),List.of("https://portal.example/out"),false);
        var store=new JdbcOAuth2AuthorizationService(new JdbcTemplate(source),new InMemoryRegisteredClientRepository(client));
        var claims=new HashMap<String,Object>();claims.put("sub","10001");claims.put("aud",ClientPolicy.audiences("portal"));claims.put("sid","test-session");
        var token=new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"test-token",Instant.now(),Instant.now().plusSeconds(900),Set.of("openid"));
        var authorization=OAuth2Authorization.withRegisteredClient(client).id("test-authorization").principalName("10001")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).authorizedScopes(Set.of("openid"))
                .token(token,metadata -> metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME,claims)).build();
        store.save(authorization);
        var restored=store.findByToken("test-token",OAuth2TokenType.ACCESS_TOKEN);
        assertThat(restored).isNotNull();
        assertThat(restored.getAccessToken().getClaims()).containsEntry("aud",ClientPolicy.audiences("portal"));
    }
}
