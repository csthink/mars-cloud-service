package com.mars.cloud.service.auth.configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mars.auth")
public class AuthProperties {
    private String issuer;
    private final Jwk jwk = new Jwk();
    private final LocalLogin localLogin = new LocalLogin();
    private Map<String, Client> clients = new LinkedHashMap<>();
    public String getIssuer() { return issuer; }
    public void setIssuer(String value) { issuer = value; }
    public Jwk getJwk() { return jwk; }
    public LocalLogin getLocalLogin() { return localLogin; }
    public Map<String, Client> getClients() { return clients; }
    public void setClients(Map<String, Client> value) { clients = value; }
    public static class Jwk {
        private String encryptionKey;
        private String encryptionKeyId;
        public String getEncryptionKey() { return encryptionKey; }
        public void setEncryptionKey(String value) { encryptionKey = value; }
        public String getEncryptionKeyId() { return encryptionKeyId; }
        public void setEncryptionKeyId(String value) { encryptionKeyId = value; }
    }
    public static class LocalLogin {
        private boolean enabled;
        private String userId;
        private String password;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public String getUserId() { return userId; }
        public void setUserId(String value) { userId = value; }
        public String getPassword() { return password; }
        public void setPassword(String value) { password = value; }
    }
    public static class Client {
        private List<String> redirectUris = List.of();
        private List<String> postLogoutRedirectUris = List.of();
        public List<String> getRedirectUris() { return redirectUris; }
        public void setRedirectUris(List<String> value) { redirectUris = value; }
        public List<String> getPostLogoutRedirectUris() { return postLogoutRedirectUris; }
        public void setPostLogoutRedirectUris(List<String> value) { postLogoutRedirectUris = value; }
    }
}
