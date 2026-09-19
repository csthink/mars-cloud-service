package com.mars.cloud.service.upms.interfaces;

import com.mars.cloud.service.upms.UpmsApplication;
import com.mars.cloud.service.upms.application.dto.DecisionOutcome;
import com.mars.cloud.service.upms.application.service.DecisionService;
import com.mars.cloud.service.upms.interfaces.dto.DecisionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {
                UpmsApplication.class,
                DecisionEndpointInternalErrorTest.ThrowingDecisionServiceConfig.class
        },
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "springdoc.api-docs.enabled=false",
                "springdoc.swagger-ui.enabled=false"
        }
)
@AutoConfigureMockMvc
class DecisionEndpointInternalErrorTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unexpectedDecisionServiceExceptionUses500TextualCodeWithoutDecision() throws Exception {
        mockMvc.perform(post("/upms/v1/decision")
                        .contextPath("/upms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "caller_id": "caller-allow",
                                  "action": "write",
                                  "resource": "sinan:namespace/workload"
                                }
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("internal_error"))
                .andExpect(jsonPath("$.code").value(not(matchesPattern("\\d+"))))
                .andExpect(jsonPath("$.result.decision").doesNotExist());
    }

    @TestConfiguration
    static class ThrowingDecisionServiceConfig {

        @Bean
        @Primary
        DecisionService throwingDecisionService() {
            return new DecisionService(null, null, null) {
                @Override
                public DecisionOutcome decide(DecisionRequest request) {
                    throw new IllegalStateException("test-controlled internal failure");
                }
            };
        }
    }
}
