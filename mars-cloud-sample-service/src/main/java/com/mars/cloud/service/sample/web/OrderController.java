package com.mars.cloud.service.sample.web;

import com.mars.cloud.mvc.exception.BusinessException;
import com.mars.cloud.mvc.exception.ResourceNotFoundException;
import com.mars.cloud.service.sample.error.SampleErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.Map;

/**
 * 示例端点：演示统一响应信封、异常映射与 i18n。
 *
 * <p>注意这里**没有**一处 try/catch，也**没有**手工拼装信封——控制器只管返回业务对象，
 * 包装与异常翻译由 {@code mars-cloud-mvc-spring-boot-starter} 完成。
 */
@RestController
@RequestMapping("/v1/orders")
public class OrderController {

    /**
     * 成功路径：返回业务对象，框架自动包成信封。
     *
     * <pre>
     * GET /sample/v1/orders/1
     * 200 {"success":true,"result":{"id":"1","sku":"demo-sku","quantity":2}}
     * </pre>
     */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return Map.of("id", id, "sku", "demo-sku", "quantity", 2);
    }

    /**
     * 业务规则拒绝：HTTP **200** + {@code success:false}。
     *
     * <p>这是本项目最容易记错的一条约定——业务拒绝不是协议错误，所以状态码仍是 200，
     * 由信封的 {@code success} 与 {@code code} 表达。文案来自 i18n 资源。
     */
    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody CreateOrderRequest request) {
        if ("out-of-stock".equals(request.sku())) {
            throw new BusinessException(SampleErrorCode.OUT_OF_STOCK);
        }
        return Map.of("id", "1001", "sku", request.sku(), "quantity", request.quantity());
    }

    /**
     * 资源不存在：映射为 HTTP 404。
     *
     * <p>{@link ResourceNotFoundException} 自带 404，不需要在控制器上标注
     * {@code @ResponseStatus}，也不需要写异常处理器。
     */
    @GetMapping("/missing")
    public Map<String, Object> missing() {
        throw new ResourceNotFoundException(SampleErrorCode.RESOURCE_NOT_FOUND);
    }
}
