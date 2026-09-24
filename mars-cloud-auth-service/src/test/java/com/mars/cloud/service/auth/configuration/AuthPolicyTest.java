package com.mars.cloud.service.auth.configuration;

import com.mars.cloud.service.auth.domain.ClientPolicy;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import static org.assertj.core.api.Assertions.*;

class AuthPolicyTest {
    static AuthProperties properties() {
        var p=new AuthProperties(); p.setIssuer("https://127.0.0.1:8301");
        p.getJwk().setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        p.getJwk().setEncryptionKeyId("test");
        for (String id:ClientPolicy.FORMAL) {
            var client=new AuthProperties.Client();
            String uri=ClientPolicy.nativeClient(id)?"http://127.0.0.1:8409/callback":"https://client.example/callback";
            client.setRedirectUris(List.of(uri));client.setPostLogoutRedirectUris(List.of(uri));p.getClients().put(id,client);
        }
        return p;
    }
    @Test void productAudiencesDoNotGrantPurchasingOrAdministration() {
        var expected=Set.of("mars-cloud-gateway","mars-cloud-auth-service","mars-cloud-product-service","mars-cloud-notice-service");
        for (String client:List.of("flippo-book","wonder-lab","english-word-card","csthink-assistant"))
            assertThat(ClientPolicy.audiences(client)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(ClientPolicy.audiences("portal")).contains("mars-cloud-order-service","mars-cloud-support-service").doesNotContain("mars-cloud-upms-service");
        assertThat(ClientPolicy.audiences("console")).contains("mars-cloud-order-service","mars-cloud-upms-service").doesNotContain("mars-cloud-support-service");
    }
    @Test void browserAndNativeTokenPoliciesDiffer() {
        var browser=ClientPolicy.create("portal",List.of("https://client.example/cb"),List.of("https://client.example/out"),false);
        var nativeApp=ClientPolicy.create("csthink-assistant",List.of("http://127.0.0.1:8409/cb"),List.of("http://127.0.0.1:8409/out"),false);
        assertThat(browser.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(nativeApp.getAuthorizationGrantTypes()).contains(AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(browser.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(nativeApp.getClientSettings().isRequireAuthorizationConsent()).isTrue();
        assertThat(nativeApp.getTokenSettings().isReuseRefreshTokens()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"http://client.example/cb","https://client.example/*","https://client.example/cb#fragment","https://user@client.example/cb","https://localhost/cb","https://localhost./cb","https://127.0.0.2/cb","https://[::1]/cb","https://app.localhost/cb","https://127.0.0.1/cb","https://client.example"})
    void invalidWebRedirectsAreRejected(String uri) {
        assertThatThrownBy(() -> ClientPolicy.create("portal",List.of(uri),List.of(uri),false)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"http://localhost:8409/cb","https://client.example/cb","http://127.0.0.1/cb","http://127.0.0.1:8409/cb#fragment"})
    void invalidNativeRedirectsAreRejected(String uri) {
        assertThatThrownBy(() -> ClientPolicy.create("csthink-assistant",List.of(uri),List.of(uri),false)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void localIssuerMustMatchPortAndLocalLoginCannotLeakToProduction() {
        var p=properties();var environment=new MockEnvironment().withProperty("server.ssl.enabled","true").withProperty("server.port","8301");environment.setActiveProfiles("local");
        assertThat(new AuthEnvironment(p,environment).local()).isTrue();
        environment.setProperty("server.port","8302");assertThatThrownBy(() -> new AuthEnvironment(p,environment)).isInstanceOf(IllegalStateException.class);
        p.setIssuer("https://auth.example");p.getLocalLogin().setEnabled(true);environment.setActiveProfiles("local","prod");
        assertThatThrownBy(() -> new AuthEnvironment(p,environment)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Local login");
    }
    @ParameterizedTest @ValueSource(strings={"https://localhost.","https://127.0.0.2","https://[::1]","https://app.localhost"})
    void productionRejectsLocalIssuers(String issuer) {
        var p=properties();p.setIssuer(issuer);var e=new MockEnvironment();e.setActiveProfiles("production");
        assertThatThrownBy(() -> new AuthEnvironment(p,e)).hasMessageContaining("Issuer");
    }
    @Test void localIssuerRequiresAnHttpsListener() {
        var p=properties();var e=new MockEnvironment().withProperty("server.port","8301");e.setActiveProfiles("local");
        assertThatThrownBy(() -> new AuthEnvironment(p,e)).hasMessageContaining("HTTPS listener");
    }
    @Test void untrustedForwardedHeadersAreDisabled() {
        var p=properties();var e=new MockEnvironment().withProperty("server.ssl.enabled","true").withProperty("server.port","8301").withProperty("server.forward-headers-strategy","framework");e.setActiveProfiles("test");
        assertThatThrownBy(() -> new AuthEnvironment(p,e)).hasMessageContaining("Forwarded headers");
    }
    @Test void missingClientsAndEncryptionInputsFailBeforeMigration() {
        var p=properties();var e=new MockEnvironment().withProperty("server.ssl.enabled","true").withProperty("server.port","8301");e.setActiveProfiles("test");
        p.getClients().remove("portal");assertThatThrownBy(() -> new AuthEnvironment(p,e)).hasMessageContaining("six clients");
        p.getJwk().setEncryptionKey("invalid");assertThatThrownBy(() -> new AuthEnvironment(p,e)).hasMessageContaining("256-bit");
    }
}
