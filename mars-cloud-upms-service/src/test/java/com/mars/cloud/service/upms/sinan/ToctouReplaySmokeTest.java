package com.mars.cloud.service.upms.sinan;

import com.jayway.jsonpath.JsonPath;
import com.mars.cloud.service.upms.UpmsApplication;
import com.mars.cloud.service.upms.infrastructure.snapshot.InMemorySnapshotProvider;
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
class ToctouReplaySmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemorySnapshotProvider snapshotProvider;

    @Test
    void sameConfirmTimeRequestCanFlipWhenOnlyActiveSnapshotChanges() throws Exception {
        snapshotProvider.publishCandidate(SinanDecisionReplayFixtures.activeSnapshot("snapshot-a"));
        MvcResult before = mockMvc.perform(sameDecisionRequest()).andReturn();

        snapshotProvider.publishCandidate(SinanDecisionReplayFixtures.noGrantSnapshot("snapshot-b"));
        MvcResult after = mockMvc.perform(sameDecisionRequest()).andReturn();

        assertDecision(before, "allow", "granted");
        assertDecision(after, "deny", "no_grant");
    }

    private static void assertDecision(MvcResult result, String decision, String reasonCode) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(body, "$.success")).isTrue();
        assertThat((String) JsonPath.read(body, "$.result.decision")).isEqualTo(decision);
        assertThat((String) JsonPath.read(body, "$.result.reason_code")).isEqualTo(reasonCode);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder sameDecisionRequest() {
        return post("/upms/v1/decision")
                .contextPath("/upms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "caller_id": "sinan-writer",
                          "action": "write",
                          "resource": "sinan:namespace/workload"
                        }
                        """);
    }
}
