package com.mars.cloud.service.upms.sinan;

import com.jayway.jsonpath.JsonPath;
import com.mars.cloud.service.upms.UpmsApplication;
import com.mars.cloud.service.upms.domain.decision.CanonicalValidator;
import com.mars.cloud.service.upms.domain.decision.DecisionEvaluator;
import com.mars.cloud.service.upms.domain.decision.ResourceId;
import com.mars.cloud.service.upms.domain.snapshot.DecisionSnapshot;
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

import static org.assertj.core.api.Assertions.assertThat;
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
class OfflineSharedValidatorReplayTest {

    private final DecisionEvaluator evaluator = new DecisionEvaluator();
    private final CanonicalValidator canonicalValidator = new CanonicalValidator();
    private final DecisionSnapshot snapshot = SinanDecisionReplayFixtures.activeSnapshot("m2-offline");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemorySnapshotProvider snapshotProvider;

    @BeforeEach
    void publishReplaySnapshot() {
        snapshotProvider.publishCandidate(snapshot);
    }

    @Test
    void offlineEvaluatorReproducesEndpointDecisionAndReasonCode() throws Exception {
        for (OfflineCase replayCase : offlineCases()) {
            com.mars.cloud.service.upms.domain.decision.DecisionOutcome offline = evaluator.evaluate(
                    snapshot,
                    replayCase.callerId(),
                    replayCase.action(),
                    ResourceId.parse(replayCase.resource(), canonicalValidator)
            );
            MvcResult endpoint = mockMvc.perform(decisionPost(replayCase)).andReturn();
            String body = endpoint.getResponse().getContentAsString();

            assertThat(endpoint.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) JsonPath.read(body, "$.result.decision")).isEqualTo(offline.decision());
            assertThat((String) JsonPath.read(body, "$.result.reason_code")).isEqualTo(offline.reasonCode());
        }
    }

    private static List<OfflineCase> offlineCases() {
        return List.of(
                new OfflineCase("sinan-writer", "write", "sinan:namespace/workload"),
                new OfflineCase("sinan-outsider", "write", "sinan:namespace/workload"),
                new OfflineCase("sinan-writer", "write", "unknown:namespace/workload"),
                new OfflineCase("sinan-writer", "delete", "sinan:namespace/workload"),
                new OfflineCase("sinan-writer", "write", "sinan:namespace/missing")
        );
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder decisionPost(OfflineCase replayCase) {
        return post("/upms/v1/decision")
                .contextPath("/upms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "caller_id": "%s",
                          "action": "%s",
                          "resource": "%s"
                        }
                        """.formatted(replayCase.callerId(), replayCase.action(), replayCase.resource()));
    }

    private record OfflineCase(String callerId, String action, String resource) {
    }
}
