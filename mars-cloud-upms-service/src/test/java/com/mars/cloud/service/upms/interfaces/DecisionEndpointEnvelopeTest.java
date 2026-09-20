package com.mars.cloud.service.upms.interfaces;

import com.mars.cloud.service.upms.UpmsApplication;
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
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = UpmsApplication.class,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "springdoc.api-docs.enabled=false",
                "springdoc.swagger-ui.enabled=false"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class DecisionEndpointEnvelopeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemorySnapshotProvider snapshotProvider;

    @BeforeEach
    void publishActiveSnapshot() {
        snapshotProvider.publishCandidate(activeSnapshot());
    }

    @Test
    void allowDecisionUsesSuccessEnvelopeWithoutCodeOrDoubleWrapping() throws Exception {
        mockMvc.perform(decisionPost()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "caller_id": "caller-allow",
                                  "action": "write",
                                  "resource": "platform-a:namespace/workload"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.decision").value("allow"))
                .andExpect(jsonPath("$.result.reason_code").value("granted"))
                .andExpect(jsonPath("$.result.decision_id").isString())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.result.success").doesNotExist());
    }

    @Test
    void denyDecisionIsSuccessEnvelopeAndNeverErrorChannel() throws Exception {
        mockMvc.perform(decisionPost()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "caller_id": "caller-without-grant",
                                  "action": "write",
                                  "resource": "platform-a:namespace/workload"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.decision").value("deny"))
                .andExpect(jsonPath("$.result.reason_code").value("no_grant"))
                .andExpect(jsonPath("$.result.decision_id").isString())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void missingFieldUsesTextualBadRequestCodeWithoutDecision() throws Exception {
        mockMvc.perform(decisionPost()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "write",
                                  "resource": "platform-a:namespace/workload"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("missing_field"))
                .andExpect(jsonPath("$.code").value(not(matchesPattern("\\d+"))))
                .andExpect(jsonPath("$.result.decision").doesNotExist());
    }

    @Test
    void emptyFieldUsesTextualBadRequestCodeWithoutDecision() throws Exception {
        mockMvc.perform(decisionPost()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "caller_id": "",
                                  "action": "write",
                                  "resource": "platform-a:namespace/workload"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("empty_field"))
                .andExpect(jsonPath("$.code").value(not(matchesPattern("\\d+"))))
                .andExpect(jsonPath("$.result.decision").doesNotExist());
    }

    @Test
    void malformedJsonUsesTextualMalformedRequestCodeWithoutDecision() throws Exception {
        mockMvc.perform(decisionPost()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caller_id\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("malformed_request"))
                .andExpect(jsonPath("$.code").value(not(matchesPattern("\\d+"))))
                .andExpect(jsonPath("$.result.decision").doesNotExist());
    }

    @Test
    void wrongMethodUsesTextualMalformedRequestCodeWithoutDecision() throws Exception {
        mockMvc.perform(get("/upms/v1/decision").contextPath("/upms"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("malformed_request"))
                .andExpect(jsonPath("$.code").value(not(matchesPattern("\\d+"))))
                .andExpect(jsonPath("$.result.decision").doesNotExist());
    }

    @Test
    void unsupportedMediaTypeUsesTextualMalformedRequestCodeWithoutDecision() throws Exception {
        mockMvc.perform(decisionPost()
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("malformed_request"))
                .andExpect(jsonPath("$.code").value(not(matchesPattern("\\d+"))))
                .andExpect(jsonPath("$.result.decision").doesNotExist());
    }

    @Test
    void platformFieldIsRejectedAsMalformedRequest() throws Exception {
        mockMvc.perform(decisionPost()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "caller_id": "caller-allow",
                                  "platform": "platform-a",
                                  "action": "write",
                                  "resource": "platform-a:namespace/workload"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("malformed_request"))
                .andExpect(jsonPath("$.result.decision").doesNotExist());
    }

    @Test
    void malformedResourceUsesTextualBadRequestCodeWithoutDecision() throws Exception {
        mockMvc.perform(decisionPost()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "caller_id": "caller-allow",
                                  "action": "write",
                                  "resource": "missing-platform-prefix"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("malformed_resource"))
                .andExpect(jsonPath("$.code").value(not(matchesPattern("\\d+"))))
                .andExpect(jsonPath("$.result.decision").doesNotExist());
    }

    @Test
    void nonCanonicalResourceUsesMalformedResourceCodeWithoutDecision() throws Exception {
        mockMvc.perform(decisionPost()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "caller_id": "caller-allow",
                                  "action": "write",
                                  "resource": " platform-a:namespace/workload"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("malformed_resource"))
                .andExpect(jsonPath("$.code").value(not(matchesPattern("\\d+"))))
                .andExpect(jsonPath("$.result.decision").doesNotExist());
    }

    @Test
    void snapshotUnavailableUses503TextualCodeWithoutDecision() throws Exception {
        snapshotProvider.markActiveCorrupted();

        mockMvc.perform(decisionPost()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "caller_id": "caller-allow",
                                  "action": "write",
                                  "resource": "platform-a:namespace/workload"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("snapshot_unavailable"))
                .andExpect(jsonPath("$.code").value(not(matchesPattern("\\d+"))))
                .andExpect(jsonPath("$.result.decision").doesNotExist());
    }

    @Test
    void unknownRegisteredStateUsesDenyReasonsOnHealthySnapshot() throws Exception {
        mockMvc.perform(decisionPost()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "caller_id": "caller-allow",
                                  "action": "write",
                                  "resource": "unknown:namespace/workload"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.decision").value("deny"))
                .andExpect(jsonPath("$.result.reason_code").value("unregistered_platform"));

        mockMvc.perform(decisionPost()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "caller_id": "caller-allow",
                                  "action": "delete",
                                  "resource": "platform-a:namespace/workload"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.decision").value("deny"))
                .andExpect(jsonPath("$.result.reason_code").value("unregistered_action"));

        mockMvc.perform(decisionPost()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "caller_id": "caller-allow",
                                  "action": "write",
                                  "resource": "platform-a:namespace/other"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.decision").value("deny"))
                .andExpect(jsonPath("$.result.reason_code").value("unregistered_resource"));
    }

    @Test
    void literalWildcardGrantDoesNotExpand() throws Exception {
        snapshotProvider.publishCandidate(literalWildcardSnapshot());

        mockMvc.perform(decisionPost()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "caller_id": "caller-allow",
                                  "action": "write",
                                  "resource": "platform-a:workload:*"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.decision").value("allow"));

        mockMvc.perform(decisionPost()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "caller_id": "caller-allow",
                                  "action": "write",
                                  "resource": "platform-a:workload:a"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.decision").value("deny"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder decisionPost() {
        return post("/upms/v1/decision").contextPath("/upms");
    }

    private static DecisionSnapshot activeSnapshot() {
        return new DecisionSnapshot(
                "2026-09-19-active",
                List.of(new PlatformRegistry("platform-a", Set.of("write"), Set.of("namespace/workload"))),
                List.of(role("platform-a", "Writer", grant("platform-a", "write", "namespace/workload"))),
                Map.of("caller-allow", Set.of(new PlatformRoleKey("platform-a", "Writer")))
        );
    }

    private static DecisionSnapshot literalWildcardSnapshot() {
        return new DecisionSnapshot(
                "2026-09-19-literal",
                List.of(new PlatformRegistry("platform-a", Set.of("write"), Set.of("workload:*", "workload:a"))),
                List.of(role("platform-a", "WildcardLiteral", grant("platform-a", "write", "workload:*"))),
                Map.of("caller-allow", Set.of(new PlatformRoleKey("platform-a", "WildcardLiteral")))
        );
    }

    private static RoleDefinition role(String platform, String name, Grant grant) {
        return new RoleDefinition(new PlatformRoleKey(platform, name), List.of(grant));
    }

    private static Grant grant(String platform, String action, String resource) {
        return new Grant(platform, action, resource);
    }
}
