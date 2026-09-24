package com.mars.cloud.service.auth.infrastructure.migration;

import com.mars.cloud.service.auth.configuration.AuthEnvironment;
import com.mars.cloud.service.auth.configuration.AuthProperties;
import com.mars.cloud.service.auth.domain.ClientPolicy;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

@Component
public class V2__RegisterClients extends BaseJavaMigration {
    private final AuthProperties properties;
    public V2__RegisterClients(AuthProperties properties, AuthEnvironment environment) { this.properties = properties; }
    @Override public Integer getChecksum() { return 1; }
    @Override public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().prepareStatement(
                "INSERT INTO sys_client (id,client_id,client_name,redirect_uris,post_logout_redirect_uris,native_client,test_client,enabled) VALUES (?,?,?,?,?,?,?,true)")) {
            for (var entry : properties.getClients().entrySet()) {
                String id = entry.getKey();
                statement.setString(1,id); statement.setString(2,id); statement.setString(3,id);
                statement.setString(4,String.join("\n",entry.getValue().getRedirectUris()));
                statement.setString(5,String.join("\n",entry.getValue().getPostLogoutRedirectUris()));
                statement.setBoolean(6,ClientPolicy.nativeClient(id)); statement.setBoolean(7,ClientPolicy.TEST.contains(id));
                statement.executeUpdate();
            }
        }
    }
}
