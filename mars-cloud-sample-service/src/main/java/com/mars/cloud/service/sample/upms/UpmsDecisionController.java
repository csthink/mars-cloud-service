package com.mars.cloud.service.sample.upms;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 演示 sample 经服务发现调用 UPMS，返回值仍由 MVC starter 包装为统一信封。
 */
@RestController
@RequestMapping("/v1/upms")
public final class UpmsDecisionController {

    private final UpmsDecisionAdapter adapter;

    public UpmsDecisionController(UpmsDecisionAdapter adapter) {
        this.adapter = adapter;
    }

    @PostMapping("/decision")
    public UpmsDecisionResult decide(@Valid @RequestBody SampleDecisionRequest request) {
        return adapter.decide(request);
    }
}
