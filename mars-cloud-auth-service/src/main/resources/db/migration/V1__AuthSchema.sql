CREATE TABLE sys_user (
 user_id BIGINT PRIMARY KEY,
 phone VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 real_name_verified BOOLEAN NOT NULL DEFAULT false,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_user_phone UNIQUE (phone),
 CONSTRAINT ck_user_phone CHECK (phone IS NULL OR REGEXP_LIKE(phone, '^\\+[1-9][0-9]{1,14}$', 'c'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;
CREATE TABLE sys_user_identity (
 identity_id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL,
 channel VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 identity_scope VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 subject VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
 attributes JSON NULL, verified_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_identity_user FOREIGN KEY (user_id) REFERENCES sys_user(user_id),
 CONSTRAINT uk_identity_subject UNIQUE (channel, identity_scope, subject),
 INDEX idx_identity_user (user_id),
 CONSTRAINT ck_identity_channel CHECK (REGEXP_LIKE(channel, '^[A-Z][A-Z0-9_]{0,63}$', 'c')),
 CONSTRAINT ck_identity_scope CHECK (REGEXP_LIKE(identity_scope, '^[A-Za-z0-9][A-Za-z0-9:._/-]{0,127}$', 'c')),
 CONSTRAINT ck_identity_subject CHECK (CHAR_LENGTH(subject)>0),
 CONSTRAINT ck_identity_attributes CHECK (attributes IS NULL OR (JSON_TYPE(attributes)='OBJECT' AND OCTET_LENGTH(CAST(attributes AS CHAR))<=8192))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;
CREATE TABLE sys_user_credential (
 credential_id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL,
 type VARCHAR(32) NOT NULL, secret_hash VARCHAR(255) NOT NULL,
 CONSTRAINT fk_credential_user FOREIGN KEY (user_id) REFERENCES sys_user(user_id),
 CONSTRAINT uk_user_credential UNIQUE (user_id,type)
) ENGINE=InnoDB;
CREATE TABLE sys_client (
 id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 client_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 client_name VARCHAR(200) NOT NULL, redirect_uris TEXT NOT NULL, post_logout_redirect_uris TEXT NOT NULL,
 native_client BOOLEAN NOT NULL, test_client BOOLEAN NOT NULL, enabled BOOLEAN NOT NULL
) ENGINE=InnoDB;
CREATE TABLE sys_login_log (
 log_id BIGINT PRIMARY KEY, user_id BIGINT NULL, event VARCHAR(64) NOT NULL,
 result VARCHAR(32) NOT NULL, channel VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 client_id VARCHAR(100) NULL, ip VARCHAR(45) NULL, user_agent VARCHAR(512) NULL,
 created_at DATETIME(6) NOT NULL, details JSON NULL,
 INDEX idx_login_user_time (user_id,created_at),
 CONSTRAINT ck_login_details CHECK (details IS NULL OR JSON_TYPE(details)='OBJECT')
) ENGINE=InnoDB;
CREATE TABLE sys_session (
 session_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 user_id BIGINT NOT NULL, client_id VARCHAR(100) NULL, kind VARCHAR(16) NOT NULL,
 device_label VARCHAR(200) NULL, ip VARCHAR(45) NULL,
 created_at DATETIME(6) NOT NULL, last_seen_at DATETIME(6) NOT NULL,
 revoked_at DATETIME(6) NULL, revoke_reason VARCHAR(64) NULL,
 INDEX idx_session_user_seen (user_id,last_seen_at),
 CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES sys_user(user_id)
) ENGINE=InnoDB;
CREATE TABLE sys_jwk (
 kid VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 public_jwk TEXT NOT NULL, private_ciphertext BLOB NOT NULL, nonce VARBINARY(12) NOT NULL,
 encryption_key_id VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL, retired_at DATETIME(6) NULL,
 active_key TINYINT NULL,
 CONSTRAINT uk_active_signing_key UNIQUE (active_key),
 CONSTRAINT ck_active_signing_key CHECK ((active_key=1 AND retired_at IS NULL) OR (active_key IS NULL AND retired_at IS NOT NULL))
) ENGINE=InnoDB;

/*
IMPORTANT:
    If using PostgreSQL:
        - update ALL columns defined with 'blob' to 'text', as PostgreSQL does not support the 'blob' data type.
        - update ALL columns defined with 'timestamp' to 'timestamptz', to ensure that time instants are stored accurately.
    If using MySQL:
        - add 'preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' to JDBC connection URL
          to ensure that time instants are stored accurately. See https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html
*/
CREATE TABLE oauth2_authorization (
    id varchar(100) NOT NULL,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000) DEFAULT NULL,
    attributes blob DEFAULT NULL,
    state varchar(500) DEFAULT NULL,
    authorization_code_value blob DEFAULT NULL,
    authorization_code_issued_at timestamp DEFAULT NULL,
    authorization_code_expires_at timestamp DEFAULT NULL,
    authorization_code_metadata blob DEFAULT NULL,
    access_token_value blob DEFAULT NULL,
    access_token_issued_at timestamp DEFAULT NULL,
    access_token_expires_at timestamp DEFAULT NULL,
    access_token_metadata blob DEFAULT NULL,
    access_token_type varchar(100) DEFAULT NULL,
    access_token_scopes varchar(1000) DEFAULT NULL,
    oidc_id_token_value blob DEFAULT NULL,
    oidc_id_token_issued_at timestamp DEFAULT NULL,
    oidc_id_token_expires_at timestamp DEFAULT NULL,
    oidc_id_token_metadata blob DEFAULT NULL,
    refresh_token_value blob DEFAULT NULL,
    refresh_token_issued_at timestamp DEFAULT NULL,
    refresh_token_expires_at timestamp DEFAULT NULL,
    refresh_token_metadata blob DEFAULT NULL,
    user_code_value blob DEFAULT NULL,
    user_code_issued_at timestamp DEFAULT NULL,
    user_code_expires_at timestamp DEFAULT NULL,
    user_code_metadata blob DEFAULT NULL,
    device_code_value blob DEFAULT NULL,
    device_code_issued_at timestamp DEFAULT NULL,
    device_code_expires_at timestamp DEFAULT NULL,
    device_code_metadata blob DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);

CREATE INDEX idx_authorization_principal ON oauth2_authorization(principal_name);
