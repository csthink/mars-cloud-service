package com.mars.cloud.service.upms.interfaces;

import com.mars.cloud.service.upms.UpmsApplication;
import com.mars.cloud.service.upms.application.dto.ConsumerDecisionClassification;
import com.mars.cloud.service.upms.application.service.ConsumerResponseClassifier;
import com.mars.cloud.service.upms.infrastructure.snapshot.InMemorySnapshotProvider;
import com.mars.cloud.service.upms.sinan.SinanDecisionReplayFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(
        classes = UpmsApplication.class,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "springdoc.api-docs.enabled=false",
                "springdoc.swagger-ui.enabled=false"
        }
)
@AutoConfigureMockMvc
class ErrorChannelMatrixTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemorySnapshotProvider snapshotProvider;

    private final ConsumerResponseClassifier classifier = new ConsumerResponseClassifier();

    @BeforeEach
    void publishReplaySnapshot() {
        snapshotProvider.publishCandidate(SinanDecisionReplayFixtures.activeSnapshot("m2-error-matrix"));
    }

    @Test
    void invalidRequestsClassifyAsIntegrationBugAndNeverDecision() throws Exception {
        assertClassification(
                postJson("""
                        {"action":"write","resource":"sinan:namespace/workload"}
                        """),
                ConsumerDecisionClassification.Type.INTEGRATION_BUG
        );
        assertClassification(
                postJson("""
                        {"caller_id":"","action":"write","resource":"sinan:namespace/workload"}
                        """),
                ConsumerDecisionClassification.Type.INTEGRATION_BUG
        );
        assertClassification(
                post("/upms/v1/decision")
                        .contextPath("/upms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caller_id\":"),
                ConsumerDecisionClassification.Type.INTEGRATION_BUG
        );
        assertClassification(
                postJson("""
                        {"caller_id":"sinan-writer","action":"write","resource":"missing-platform-prefix"}
                        """),
                ConsumerDecisionClassification.Type.INTEGRATION_BUG
        );
    }

    @Test
    void healthyUnknownsAreDenyDecisionsNotErrors() throws Exception {
        assertDecision(postJson("""
                {"caller_id":"sinan-writer","action":"write","resource":"unknown:namespace/workload"}
                """), "deny");
        assertDecision(postJson("""
                {"caller_id":"sinan-writer","action":"delete","resource":"sinan:namespace/workload"}
                """), "deny");
        assertDecision(postJson("""
                {"caller_id":"sinan-writer","action":"write","resource":"sinan:namespace/missing"}
                """), "deny");
    }

    @Test
    void serviceStateErrorsClassifyAsServiceFailureAndNeverDecision() throws Exception {
        snapshotProvider.markActiveCorrupted();

        assertClassification(
                postJson("""
                        {"caller_id":"sinan-writer","action":"write","resource":"sinan:namespace/workload"}
                        """),
                ConsumerDecisionClassification.Type.SERVICE_FAILURE
        );
    }

    @Test
    void unsupportedSpringShapesClassifyAsProtocolErrorAndNeverDecision() throws Exception {
        assertClassification(
                get("/upms/v1/decision").contextPath("/upms"),
                ConsumerDecisionClassification.Type.PROTOCOL_ERROR
        );
        assertClassification(
                post("/upms/v1/decision")
                        .contextPath("/upms")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"),
                ConsumerDecisionClassification.Type.PROTOCOL_ERROR
        );
    }

    private void assertDecision(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String expectedDecision
    ) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        ConsumerDecisionClassification classification = classifier.classify(
                result.getResponse().getStatus(),
                result.getResponse().getContentAsString()
        );
        assertThat(classification.type()).isEqualTo(ConsumerDecisionClassification.Type.DECISION);
        assertThat(classification.decision()).isEqualTo(expectedDecision);
    }

    private void assertClassification(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            ConsumerDecisionClassification.Type expectedType
    ) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        ConsumerDecisionClassification classification = classifier.classify(
                result.getResponse().getStatus(),
                result.getResponse().getContentAsString()
        );
        assertThat(classification.type()).isEqualTo(expectedType);
        if (expectedType != ConsumerDecisionClassification.Type.DECISION) {
            assertThat(classification.decision()).isNull();
        }
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postJson(String body) {
        return post("/upms/v1/decision")
                .contextPath("/upms")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
