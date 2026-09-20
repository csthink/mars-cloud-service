package com.mars.cloud.service.upms.interfaces;

import com.mars.cloud.service.upms.UpmsApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = UpmsApplication.class,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "springdoc.api-docs.enabled=false",
                "springdoc.swagger-ui.enabled=false",
                // 本用例断言的就是「没有活动快照」这条服务态路径，
                // 必须关掉 local profile 的开发种子快照。
                "mars.upms.local-fixture.enabled=false"
        }
)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@ActiveProfiles({"local", "test"})
class DecisionEndpointNoActiveSnapshotTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void noActiveSnapshotUses503TextualCodeWithoutDecision() throws Exception {
        mockMvc.perform(post("/upms/v1/decision")
                        .contextPath("/upms")
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
}
