package com.mars.cloud.service.upms.security;

import com.mars.cloud.security.PdpClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles({"local", "test"})
class UpmsSecurityContractTest extends SecurityTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Test void rejectsMissingTokenAndAllowsOnlyHealthProbeAnonymously() throws Exception {
        mvc.perform(post("/upms/v1/decision").contextPath("/upms")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("62001"));
        mvc.perform(get("/upms/actuator/health").contextPath("/upms")).andExpect(status().isOk());
        assertThat(context.getBeansOfType(PdpClient.class)).isEmpty();
    }
    @Test void mismatchedCallerIsForbiddenBeforeDecision() throws Exception {
        mvc.perform(post("/upms/v1/decision").contextPath("/upms").header("Authorization", "Bearer " + token("alice"))
                .contentType("application/json").content("{\"caller_id\":\"bob\",\"action\":\"view\",\"resource\":\"demo:view:domain:kubernetes-ops\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("62006"));
    }
    @Test void tokenMustIncludeDownstreamAudience() throws Exception {
        mvc.perform(post("/upms/v1/decision").contextPath("/upms")
                .header("Authorization", "Bearer " + ISSUER.token("alice", "mars-cloud-sample-service"))
                .contentType("application/json").content("{\"caller_id\":\"alice\",\"action\":\"view\",\"resource\":\"demo:view:domain:kubernetes-ops\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("62002"));
    }
}
