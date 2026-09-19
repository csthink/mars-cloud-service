package com.mars.cloud.service.upms.sinan;

import com.jayway.jsonpath.JsonPath;
import com.mars.cloud.service.upms.UpmsApplication;
import com.mars.cloud.service.upms.infrastructure.snapshot.InMemorySnapshotProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
class SinanDecisionParityReplayTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemorySnapshotProvider snapshotProvider;

    @BeforeEach
    void publishReplaySnapshot() {
        snapshotProvider.publishCandidate(SinanDecisionReplayFixtures.activeSnapshot("m2-sinan-replay"));
    }

    @Test
    void replayFixtureMatchesExpectedSinanStubDecisionsWithZeroMismatch() throws Exception {
        for (SinanDecisionReplayCase replayCase : SinanDecisionReplayFixtures.replayCases()) {
            MvcResult result = mockMvc.perform(decisionPost(replayCase))
                    .andReturn();

            int status = result.getResponse().getStatus();
            String body = result.getResponse().getContentAsString();
            Boolean success = JsonPath.read(body, "$.success");
            String actualDecision = JsonPath.read(body, "$.result.decision");

            assertThat(status)
                    .as("status for %s", replayCase)
                    .isEqualTo(200);
            assertThat(success)
                    .as("success flag for %s", replayCase)
                    .isTrue();
            assertThat(actualDecision)
                    .as("mismatch caller=%s action=%s resource=%s expected=%s source=%s",
                            replayCase.callerId(),
                            replayCase.action(),
                            replayCase.resource(),
                            replayCase.expectedDecision(),
                            replayCase.sourceLabel())
                    .isEqualTo(replayCase.expectedDecision());
        }
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder decisionPost(
            SinanDecisionReplayCase replayCase
    ) {
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
}
