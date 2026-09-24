package com.mars.cloud.service.auth.configuration;

import com.mars.cloud.security.servlet.MarsServletSecurityConfigurer;
import com.mars.cloud.security.servlet.ServletSecurityErrors;
import com.mars.cloud.security.web.SecurityResponses;
import com.mars.cloud.service.auth.interfaces.AccountController;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(AuthResourceChainTest.Config.class)
class AuthResourceChainTest {
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @BeforeEach void setup() { mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    @Test void bearerRequestDoesNotRotateOrReplaceTheBrowserSession() throws Exception {
        var session=new MockHttpSession();
        var browser=new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated("browser-user",null,java.util.List.of()));
        session.setAttribute("SPRING_SECURITY_CONTEXT",browser);
        String id=session.getId();
        mvc.perform(get("/auth/v1/me").session(session).header("Authorization","Bearer fixture")).andExpect(status().isOk());
        assertThat(session.getId()).isEqualTo(id);
        assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isSameAs(browser);
        mvc.perform(get("/auth/v1/me").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void containerErrorDispatchPreservesItsStatusButDirectErrorAccessIsDenied() throws Exception {
        mvc.perform(get("/error").with(request -> {request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);return request;}).requestAttr("jakarta.servlet.error.status_code",401)).andExpect(status().isUnauthorized());
        mvc.perform(get("/error")).andExpect(status().isForbidden());
    }
    @org.springframework.web.bind.annotation.RestController
    static class ErrorFixture {
        @org.springframework.web.bind.annotation.GetMapping("/error")
        void error(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) {
            response.setStatus((Integer)request.getAttribute("jakarta.servlet.error.status_code"));
        }
    }
    @Configuration @EnableWebSecurity @EnableWebMvc
    static class Config {
        @Bean ErrorFixture errorFixture(){return new ErrorFixture();}
        @Bean @org.springframework.core.annotation.Order(100) SecurityFilterChain deny(HttpSecurity http) throws Exception {return new AuthorizationServerConfiguration().denyOtherRequests(http);}
        @Bean AccountController controller() { return new AccountController(); }
        @Bean @org.springframework.core.annotation.Order(2) SecurityFilterChain resource(HttpSecurity http) throws Exception {
            var decoder=(org.springframework.security.oauth2.jwt.JwtDecoder) token -> Jwt.withTokenValue(token)
                    .header("alg","RS256").subject("123").claim("client_id","portal").claim("tenant_id","default")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build();
            var errors=new ServletSecurityErrors(new SecurityResponses(new StaticMessageSource()));
            return new AuthorizationServerConfiguration().authResourceSecurity(http,new MarsServletSecurityConfigurer(decoder,errors));
        }
    }
}
