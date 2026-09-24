package com.mars.cloud.service.auth.infrastructure.client;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import com.mars.cloud.service.auth.domain.ClientPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import tools.jackson.databind.json.JsonMapper;

/** Reads identity and protocol settings from sys_client, without polymorphic JSON deserialization. */
public final class SysClientRegisteredClientRepository implements RegisteredClientRepository {
    private final JdbcTemplate jdbc;
    private final boolean local;
    private final JsonMapper json=JsonMapper.builder().build();
    public SysClientRegisteredClientRepository(JdbcTemplate jdbc,boolean local) { this.jdbc=jdbc; this.local=local; }
    @Override public void save(RegisteredClient client) { throw new UnsupportedOperationException("Client changes require a versioned migration"); }
    @Override public RegisteredClient findById(String id) { return find("id",id); }
    @Override public RegisteredClient findByClientId(String id) { return find("client_id",id); }
    private RegisteredClient find(String column,String id) {
        List<RegisteredClient> result=jdbc.query("SELECT * FROM sys_client WHERE "+column+"=? AND enabled=true",this::read,id);
        return result.isEmpty()?null:result.getFirst();
    }
    private RegisteredClient read(ResultSet rs,int row) throws SQLException {
        String id=rs.getString("client_id");
        var redirects=split(rs.getString("redirect_uris"));var logouts=split(rs.getString("post_logout_redirect_uris"));
        ClientPolicy.create(id,redirects,logouts,local);
        var client=json.readTree(rs.getString("client_settings"));var token=json.readTree(rs.getString("token_settings"));
        var methods=split(rs.getString("authentication_methods"));var grants=split(rs.getString("authorization_grant_types"));
        var scopes=split(rs.getString("scopes"));
        Set<String> expectedGrants=ClientPolicy.nativeClient(id)?Set.of("authorization_code","refresh_token"):Set.of("authorization_code");
        if (!methods.equals(List.of("none")) || !Set.copyOf(grants).equals(expectedGrants) || !scopes.equals(List.of("openid"))
                || rs.getBoolean("native_client")!=ClientPolicy.nativeClient(id) || rs.getBoolean("test_client")!=ClientPolicy.TEST.contains(id)
                || !client.isObject() || client.size()!=2 || !client.path("requireProofKey").isBoolean() || !client.path("requireProofKey").asBoolean()
                || !client.path("requireAuthorizationConsent").isBoolean() || client.path("requireAuthorizationConsent").asBoolean()!=ClientPolicy.nativeClient(id)
                || !token.isObject() || token.size()!=4 || !token.path("accessTokenSeconds").isIntegralNumber() || token.path("accessTokenSeconds").asLong()!=900
                || !token.path("refreshTokenSeconds").isIntegralNumber() || token.path("refreshTokenSeconds").asLong()!=2592000
                || !token.path("reuseRefreshTokens").isBoolean() || token.path("reuseRefreshTokens").asBoolean()
                || !"RS256".equals(token.path("idTokenSignatureAlgorithm").asText()))
            throw new IllegalStateException("Persisted client policy does not match the supported protocol policy");
        var builder=RegisteredClient.withId(rs.getString("id")).clientId(id).clientName(rs.getString("client_name"))
                .redirectUris(values -> values.addAll(redirects)).postLogoutRedirectUris(values -> values.addAll(logouts))
                .clientSettings(ClientSettings.builder().requireProofKey(client.get("requireProofKey").asBoolean())
                        .requireAuthorizationConsent(client.get("requireAuthorizationConsent").asBoolean()).build())
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofSeconds(token.get("accessTokenSeconds").asLong()))
                        .refreshTokenTimeToLive(Duration.ofSeconds(token.get("refreshTokenSeconds").asLong()))
                        .reuseRefreshTokens(token.get("reuseRefreshTokens").asBoolean())
                        .idTokenSignatureAlgorithm(SignatureAlgorithm.from(token.get("idTokenSignatureAlgorithm").asText())).build());
        methods.forEach(value -> builder.clientAuthenticationMethod(new ClientAuthenticationMethod(value)));
        grants.forEach(value -> builder.authorizationGrantType(new AuthorizationGrantType(value)));
        scopes.forEach(builder::scope);return builder.build();
    }
    private static List<String> split(String text) { return Arrays.asList(text.split("\n",-1)); }
}
