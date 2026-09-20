package com.mars.cloud.service.sample;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端契约测试：把 README 里对使用者承诺的每一条都钉住。
 *
 * <p>这个模块的定位是「可运行的文档」，所以它必须**自己验证文档是对的**——
 * 否则文档会随框架演进而悄悄失效。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class SampleEnvelopeContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void successResponseIsWrappedInEnvelope() throws Exception {
        mockMvc.perform(get("/v1/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.id").value("1"))
                // 成功响应里不应出现 code / message（@JsonInclude(NON_NULL)）
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void businessRejectionKeepsHttp200AndReportsFailureInEnvelope() throws Exception {
        mockMvc.perform(post("/v1/orders")
                        .contentType("application/json")
                        .content("{\"sku\":\"out-of-stock\",\"quantity\":1}"))
                // 业务拒绝不是协议错误：HTTP 仍是 200
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("66102"))
                .andExpect(jsonPath("$.message").value("Out of stock"));
    }

    @Test
    void notFoundMapsTo404() throws Exception {
        mockMvc.perform(get("/v1/orders/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("66101"));
    }

    @Test
    void validationFailureMapsTo400() throws Exception {
        mockMvc.perform(post("/v1/orders")
                        .contentType("application/json")
                        .content("{\"sku\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void errorMessageFollowsAcceptLanguage() throws Exception {
        mockMvc.perform(get("/v1/orders/missing").header("Accept-Language", "zh-CN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("资源不存在"));

        mockMvc.perform(get("/v1/orders/missing").header("Accept-Language", "en-US"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void happyPathCreateReturnsBusinessObject() throws Exception {
        mockMvc.perform(post("/v1/orders")
                        .contentType("application/json")
                        .content("{\"sku\":\"demo-sku\",\"quantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.quantity").value(3));
    }
}
