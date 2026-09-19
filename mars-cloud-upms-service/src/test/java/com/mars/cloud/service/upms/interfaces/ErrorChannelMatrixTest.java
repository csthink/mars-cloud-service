package com.mars.cloud.service.upms.interfaces;

import com.mars.cloud.service.upms.UpmsApplication;
import com.mars.cloud.service.upms.application.dto.ConsumerDecisionClassification;
import com.mars.cloud.service.upms.application.service.ConsumerResponseClassifier;
import com.mars.cloud.service.upms.domain.policy.Grant;
import com.mars.cloud.service.upms.domain.policy.PlatformRoleKey;
import com.mars.cloud.service.upms.domain.policy.RoleDefinition;
import com.mars.cloud.service.upms.domain.snapshot.DecisionSnapshot;
import com.mars.cloud.service.upms.domain.snapshot.PlatformRegistry;
import com.mars.cloud.service.upms.infrastructure.snapshot.InMemorySnapshotProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
    void publishActiveSnapshot() {
        snapshotProvider.publishCandidate(activeSnapshot());
    }

    /**
     * 本测试只关心「错误通道如何分类」，不关心判定内容，
     * 因此自带一份最小活动快照（平台 + 一个持有 write 授权的主体），
     * 不依赖任何共享 fixture——共享 fixture 一旦被删，这里就会跟着坏。
     */
    private static DecisionSnapshot activeSnapshot() {
        String platform = "platform-a";
        PlatformRoleKey writer = new PlatformRoleKey(platform, "Writer");
        return new DecisionSnapshot(
                "2026-09-19-error-matrix",
                List.of(new PlatformRegistry(platform, Set.of("write"), Set.of("namespace/workload"))),
                List.of(new RoleDefinition(
                        writer, List.of(new Grant(platform, "write", "namespace/workload")))),
                Map.of("platform-a-writer", Set.of(writer))
        );
    }

    @Test
    void invalidRequestsClassifyAsIntegrationBugAndNeverDecision() throws Exception {
        assertClassification(
                postJson("""
                        {"action":"write","resource":"platform-a:namespace/workload"}
                        """),
                ConsumerDecisionClassification.Type.INTEGRATION_BUG
        );
        assertClassification(
                postJson("""
                        {"caller_id":"","action":"write","resource":"platform-a:namespace/workload"}
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
                        {"caller_id":"platform-a-writer","action":"write","resource":"missing-platform-prefix"}
                        """),
                ConsumerDecisionClassification.Type.INTEGRATION_BUG
        );
    }

    @Test
    void healthyUnknownsAreDenyDecisionsNotErrors() throws Exception {
        assertDecision(postJson("""
                {"caller_id":"platform-a-writer","action":"write","resource":"unknown:namespace/workload"}
                """), "deny");
        assertDecision(postJson("""
                {"caller_id":"platform-a-writer","action":"delete","resource":"platform-a:namespace/workload"}
                """), "deny");
        assertDecision(postJson("""
                {"caller_id":"platform-a-writer","action":"write","resource":"platform-a:namespace/missing"}
                """), "deny");
    }

    @Test
    void serviceStateErrorsClassifyAsServiceFailureAndNeverDecision() throws Exception {
        snapshotProvider.markActiveCorrupted();

        assertClassification(
                postJson("""
                        {"caller_id":"platform-a-writer","action":"write","resource":"platform-a:namespace/workload"}
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
