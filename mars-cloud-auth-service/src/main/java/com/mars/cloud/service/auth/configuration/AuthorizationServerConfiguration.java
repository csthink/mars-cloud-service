package com.mars.cloud.service.auth.configuration;

import com.mars.cloud.security.jwt.IdentityTokenValidator;
import com.mars.cloud.security.servlet.MarsServletSecurityConfigurer;
import com.mars.cloud.service.auth.application.*;
import com.mars.cloud.service.auth.domain.ClientPolicy;
import com.mars.cloud.service.auth.infrastructure.sms.SmsAuthenticationFilter;
import com.mars.cloud.service.auth.application.SmsLoginService;
import com.mars.cloud.service.auth.interfaces.SmsOrigin;
import com.mars.cloud.service.auth.infrastructure.authorization.AtomicCodeAuthenticationProvider;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Clock;
import java.util.ListIterator;
import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@EnableWebSecurity
public class AuthorizationServerConfiguration {
    @Bean JWKSource<SecurityContext> jwkSource(SigningKeyService keys) {
        return (selector,context) -> selector.select(keys.signingKeys());
    }
    @Bean OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(SigningKeyService keys) { return new AuthTokenCustomizer(keys); }
    @Bean AuthorizationServerSettings authorizationServerSettings(AuthProperties properties,AuthEnvironment environment) {
        return AuthorizationServerSettings.builder().issuer(properties.getIssuer()).jwkSetEndpoint("/.well-known/jwks.json").build();
    }
    @Bean @Primary JwtDecoder authJwtDecoder(JWKSource<SecurityContext> source,AuthProperties properties,Clock clock) {
        var processor=new com.nimbusds.jwt.proc.DefaultJWTProcessor<com.nimbusds.jose.proc.SecurityContext>();
        processor.setJWSKeySelector(new com.nimbusds.jose.proc.JWSVerificationKeySelector<>(com.nimbusds.jose.JWSAlgorithm.RS256,source));
        processor.setJWTClaimsSetVerifier((claims,context) -> { });
        var decoder=new org.springframework.security.oauth2.jwt.NimbusJwtDecoder(processor);
        decoder.setJwtValidator(new IdentityTokenValidator(properties.getIssuer(),"mars-cloud-auth-service",clock));
        return decoder;
    }
    @Bean @Order(1)
    SecurityFilterChain protocolSecurity(HttpSecurity http,OAuth2AuthorizationService authorizations,
            JdbcTemplate jdbc,PlatformTransactionManager manager,JwtDecoder decoder,AuthProperties properties) throws Exception {
        var server=new OAuth2AuthorizationServerConfigurer();
        http.securityMatcher(server.getEndpointsMatcher());
        http.with(server,config -> config.oidc(oidc -> oidc.userInfoEndpoint(userInfo ->
                    userInfo.userInfoMapper(context -> new OidcUserInfo(Map.of("sub",context.getAuthorization().getPrincipalName())))))
                .authorizationEndpoint(endpoint -> endpoint.authenticationProviders(providers -> providers.forEach(provider -> {
                    if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider request)
                        request.setAuthorizationConsentRequired(context -> ClientPolicy.nativeClient(context.getRegisteredClient().getClientId()));
                })))
                .tokenEndpoint(endpoint -> endpoint.authenticationProviders(providers -> {
                    ListIterator<AuthenticationProvider> iterator=providers.listIterator();
                    while (iterator.hasNext()) {
                        AuthenticationProvider provider=iterator.next();
                        if (provider instanceof OAuth2AuthorizationCodeAuthenticationProvider)
                            iterator.set(new AtomicCodeAuthenticationProvider(provider,authorizations,jdbc,manager));
                    }
                })));
        if (properties.getLocalLogin().isEnabled()) server.authorizationEndpoint(endpoint -> endpoint.consentPage("/login/consent"));
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers(request -> "/oauth2/authorize".equals(request.getServletPath())
                        && java.util.Arrays.asList(java.util.Objects.toString(request.getParameter("prompt"), "").split(" ")).contains("none")).permitAll()
                .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoder)));
        return http.build();
    }
    @Bean @Order(2)
    SecurityFilterChain authResourceSecurity(HttpSecurity http,MarsServletSecurityConfigurer configurer) throws Exception {
        http.securityMatcher("/auth/v1/**").csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS).sessionFixation(fixation -> fixation.none()))
                .securityContext(context -> context.securityContextRepository(new org.springframework.security.web.context.NullSecurityContextRepository()))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated());
        configurer.configure(http); return http.build();
    }
    @Bean @Order(3)
    SecurityFilterChain loginSecurity(HttpSecurity http,AuthProperties properties,AuthenticationManager localLoginManager,
            AuthSessionService sessions,SmsLoginService sms,SmsOrigin origin) throws Exception {
        http.securityMatcher("/login","/login/**","/logout").authenticationManager(localLoginManager);
        var csrf=new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository();
        http.csrf(config -> config.csrfTokenRepository(csrf));
        http.addFilterBefore(new SmsAuthenticationFilter(sms,origin,sessions,csrf),
                org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        if (properties.getLocalLogin().isEnabled()) {
            var success=new SavedRequestAwareAuthenticationSuccessHandler();
            http.formLogin(form -> form.loginPage("/login").loginProcessingUrl("/login").successHandler((request,response,authentication) -> {
                try {
                    sessions.login(authentication.getName(),request);
                    success.onAuthenticationSuccess(request,response,authentication);
                } catch (RuntimeException exception) {
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                    var session=request.getSession(false); if (session!=null) session.invalidate();
                    response.sendError(503,"Cannot establish an authenticated session");
                }
            }));
        }
        http.sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()));
        return http.build();
    }
    @Bean @Order(100)
    SecurityFilterChain denyOtherRequests(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(requests -> requests.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll().anyRequest().denyAll())
                .requestCache(cache -> cache.disable()).build();
    }
}
