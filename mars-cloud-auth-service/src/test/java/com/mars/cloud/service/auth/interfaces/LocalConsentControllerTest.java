package com.mars.cloud.service.auth.interfaces;

import com.mars.cloud.service.auth.configuration.AuthProperties;
import com.mars.cloud.service.auth.domain.ClientPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import static org.assertj.core.api.Assertions.assertThat;

class LocalConsentControllerTest {
    @Test void consentUsesTheValidatedRequestPortAndBindsStateToUserAndClient() {
        var client=ClientPolicy.create("test-native",List.of("http://127.0.0.1:8309/callback"),List.of("http://127.0.0.1:8309/logged-out"),true);
        var other=ClientPolicy.create("csthink-assistant",List.of("http://127.0.0.1:8309/callback"),List.of("http://127.0.0.1:8309/logged-out"),false);
        var clients=new InMemoryRegisteredClientRepository(client,other);
        var authorizations=new InMemoryOAuth2AuthorizationService();
        var validated=OAuth2AuthorizationRequest.authorizationCode().authorizationUri("https://auth.example/oauth2/authorize")
                .clientId(client.getClientId()).redirectUri("http://127.0.0.1:8310/callback").scope("openid").state("client-state").build();
        authorizations.save(OAuth2Authorization.withRegisteredClient(client).principalName("123")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).attribute("state","consent-state")
                .attribute(OAuth2AuthorizationRequest.class.getName(),validated).build());
        var properties=new AuthProperties();properties.getLocalLogin().setEnabled(true);
        var controller=new LocalConsentController(properties,clients,authorizations);
        var authentication=UsernamePasswordAuthenticationToken.authenticated("123","unused",List.of());
        var request=new MockHttpServletRequest();request.setAttribute(CsrfToken.class.getName(),new DefaultCsrfToken("X-CSRF-TOKEN","_csrf","csrf"));
        request.addParameter("redirect_uri","https://unregistered.example/callback");
        var response=controller.consent("test-native","consent-state",authentication,request);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).contains("form-action 'self' http://127.0.0.1:8310/callback;")
                .doesNotContain(":8309", "unregistered.example", "*");
        assertThat(controller.consent("test-native","unknown-state",authentication,request).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.consent("csthink-assistant","consent-state",authentication,request).getStatusCode().value()).isEqualTo(404);
        var differentUser=UsernamePasswordAuthenticationToken.authenticated("456","unused",List.of());
        assertThat(controller.consent("test-native","consent-state",differentUser,request).getStatusCode().value()).isEqualTo(404);
    }
}
