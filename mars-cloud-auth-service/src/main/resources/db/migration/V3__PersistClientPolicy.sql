ALTER TABLE sys_client
 ADD authentication_methods VARCHAR(100) NOT NULL DEFAULT 'none',
 ADD authorization_grant_types VARCHAR(200) NOT NULL DEFAULT 'authorization_code',
 ADD scopes VARCHAR(1000) NOT NULL DEFAULT 'openid',
 ADD client_settings JSON NULL,
 ADD token_settings JSON NULL;
UPDATE sys_client SET
 authorization_grant_types=IF(native_client, CONCAT('authorization_code',CHAR(10),'refresh_token'), 'authorization_code'),
 client_settings=JSON_OBJECT('requireProofKey',true,'requireAuthorizationConsent',IF(native_client,CAST('true' AS JSON),CAST('false' AS JSON))),
 token_settings=JSON_OBJECT('accessTokenSeconds',900,'refreshTokenSeconds',2592000,'reuseRefreshTokens',false,'idTokenSignatureAlgorithm','RS256');
ALTER TABLE sys_client MODIFY client_settings JSON NOT NULL, MODIFY token_settings JSON NOT NULL;
