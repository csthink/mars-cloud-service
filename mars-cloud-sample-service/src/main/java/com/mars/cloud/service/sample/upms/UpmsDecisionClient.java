package com.mars.cloud.service.sample.upms;

import com.mars.cloud.common.response.UnifyResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 经注册中心服务名调用 UPMS。不得配置固定 URL。
 */
@FeignClient(name = UpmsDecisionClient.CLIENT_NAME, path = "/upms")
public interface UpmsDecisionClient {

    String CLIENT_NAME = "mars-cloud-upms-service";

    @PostMapping("/v1/decision")
    UnifyResponse<UpmsDecisionResult> decide(@RequestBody UpmsDecisionRequest request);
}
