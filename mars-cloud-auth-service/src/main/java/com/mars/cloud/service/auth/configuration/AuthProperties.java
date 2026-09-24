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
    private final Sms sms = new Sms();
    private Map<String, Client> clients = new LinkedHashMap<>();
    public String getIssuer() { return issuer; }
    public void setIssuer(String value) { issuer = value; }
    public Jwk getJwk() { return jwk; }
    public LocalLogin getLocalLogin() { return localLogin; }
    public Sms getSms() { return sms; }
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
    public static class Sms {
        private String hmacKey;
        private boolean mockEnabled;
        private String mockOutbox;
        private int dailyBudget = 2000;
        private int captchaErrorThreshold = 3;
        public String getHmacKey() { return hmacKey; }
        public void setHmacKey(String value) { hmacKey = value; }
        public boolean isMockEnabled() { return mockEnabled; }
        public void setMockEnabled(boolean value) { mockEnabled = value; }
        public String getMockOutbox() { return mockOutbox; }
        public void setMockOutbox(String value) { mockOutbox = value; }
        public int getDailyBudget() { return dailyBudget; }
        public void setDailyBudget(int value) { dailyBudget = value; }
        public int getCaptchaErrorThreshold() { return captchaErrorThreshold; }
        public void setCaptchaErrorThreshold(int value) { captchaErrorThreshold = value; }
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
