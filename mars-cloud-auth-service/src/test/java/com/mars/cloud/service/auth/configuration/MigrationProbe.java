package com.mars.cloud.service.auth.configuration;

import com.mars.cloud.service.auth.domain.ClientPolicy;
import com.mars.cloud.service.auth.infrastructure.migration.V2__RegisterClients;
import java.util.Base64;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

/** Runs migration and production client checks against an explicitly selected empty verification database. */
public final class MigrationProbe {
    public static void main(String[] args) {
        var ds=new DriverManagerDataSource(required("SPRING_DATASOURCE_URL"),required("SPRING_DATASOURCE_USERNAME"),required("SPRING_DATASOURCE_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);
        String database=jdbc.queryForObject("SELECT DATABASE()",String.class);
        check(database.equals(required("AUTH_VERIFY_DATABASE"))&&database.contains("_verify_"),"Explicit verification database is required");
        var p=new AuthProperties();p.setIssuer("https://auth.example");
        p.getJwk().setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));p.getJwk().setEncryptionKeyId("probe");
        for(String id:ClientPolicy.FORMAL) {
            var client=new AuthProperties.Client();
            String base=ClientPolicy.nativeClient(id)?"http://127.0.0.1:8309":"https://"+id+".example";
            client.setRedirectUris(List.of(base+"/callback"));client.setPostLogoutRedirectUris(List.of(base+"/logged-out"));p.getClients().put(id,client);
        }
        var env=new MockEnvironment();env.setActiveProfiles("production");
        var guard=new AuthEnvironment(p,env);
        var flyway=Flyway.configure().dataSource(ds).locations("classpath:db/migration").javaMigrations(new V2__RegisterClients(p,guard)).cleanDisabled(true).baselineOnMigrate(false).load();
        if(args.length==1&&args[0].equals("nonempty")) {
            jdbc.execute("CREATE TABLE IF NOT EXISTS preexisting_probe(id INTEGER PRIMARY KEY)");
            try {flyway.migrate();throw new AssertionError("Unknown nonempty schema was accepted");}
            catch(org.flywaydb.core.api.FlywayException expected) {
                check(expected.getMessage().contains("non-empty"),"Must reject unknown nonempty schema");
            }
            check(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)==1,"Nonempty refusal must preserve the schema");
            System.out.println("PASS: unknown nonempty schema refused without baseline or cleanup");return;
        }
        flyway.migrate();
        check(flyway.migrate().migrationsExecuted==0,"Second migration run must not change the schema");
        check(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)==10,"Nine application tables plus Flyway history are required");
        var repository=new AuthPersistenceConfiguration().registeredClientRepository(jdbc,guard);
        check(jdbc.queryForObject("SELECT COUNT(*) FROM sys_client",Integer.class)==6,"Production must contain exactly six clients");
        for(String id:ClientPolicy.FORMAL)check(repository.findByClientId(id)!=null,"Formal client must load");
        String old=repository.findByClientId("portal").getRedirectUris().iterator().next();
        p.getClients().get("portal").setRedirectUris(List.of("https://changed.example/callback"));
        flyway.migrate();
        check(repository.findByClientId("portal").getRedirectUris().contains(old),"Restart configuration must not rewrite registered redirects");
        var changed=Flyway.configure().dataSource(ds).locations("classpath:db/migration").javaMigrations(new V2__RegisterClientsProbe(p,guard)).load();
        check(!changed.validateWithResult().validationSuccessful,"Changed migration checksum must fail validation");
        check(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user",Integer.class)==0,"Production migration must not create a default user");
        System.out.println("PASS: production six-client policy, nine-table migration, repeat migration, immutable configuration and checksum validation");
    }
    static final class V2__RegisterClientsProbe extends V2__RegisterClients {
        V2__RegisterClientsProbe(AuthProperties p,AuthEnvironment e){super(p,e);}
        @Override public Integer getChecksum(){return 2;}
        @Override public String getDescription(){return "RegisterClients";}
    }
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+key);return value;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
