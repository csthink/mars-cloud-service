package com.mars.cloud.service.auth.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Confirms that the account authenticated by the HTTPS SMS flow was audited. */
public final class SmsAuditProbe {
    public static void main(String[] args) throws Exception {
        if (args.length!=1) throw new IllegalArgumentException("Private account ID path required");
        Path path=Path.of(args[0]);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path))
            throw new IllegalArgumentException("Private account ID file required");
        long userId=Long.parseLong(Files.readString(path).trim());
        var source=new DriverManagerDataSource(required("SPRING_DATASOURCE_URL"),
                required("SPRING_DATASOURCE_USERNAME"),required("SPRING_DATASOURCE_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        String database=jdbc.queryForObject("SELECT DATABASE()",String.class);
        check(required("AUTH_VERIFY_DATABASE").equals(database) && database.contains("_verify_"),
                "Dedicated verification database required");
        check(count(jdbc,"SELECT COUNT(*) FROM sys_user WHERE user_id=? AND status='ACTIVE'",userId)==1,
                "SMS account must be active");
        check(count(jdbc,"SELECT COUNT(*) FROM sys_user_identity WHERE user_id=? AND channel='SMS' AND identity_scope='e164'",userId)==1,
                "SMS account must own one verified phone identity");
        check(count(jdbc,"SELECT COUNT(*) FROM sys_login_log WHERE user_id=? AND event='LOGIN' AND result='SUCCESS'",userId)==1,
                "SMS login must have one audit row");
        check(count(jdbc,"SELECT COUNT(*) FROM sys_session WHERE user_id=? AND kind='BROWSER'",userId)==1,
                "SMS login must register one browser session");
        System.out.println("PASS: SMS account binding, login audit and browser session");
    }
    private static int count(JdbcTemplate jdbc,String sql,long userId) {
        return jdbc.queryForObject(sql,Integer.class,userId);
    }
    private static String required(String name) {
        String value=System.getenv(name);
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Missing "+name);
        return value;
    }
    private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
