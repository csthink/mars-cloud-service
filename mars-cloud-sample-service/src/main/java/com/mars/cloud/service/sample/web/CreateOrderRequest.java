package com.mars.cloud.service.sample.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 下单请求。
 *
 * <p>校验注解写在 DTO 上，控制器参数加 {@code @Valid} 即可——参数不合法时框架统一
 * 映射成 HTTP 400 + 信封，业务代码里**不需要**写 try/catch 或 if 判断。
 */
public record CreateOrderRequest(

        @NotBlank(message = "sku 不能为空")
        String sku,

        @Min(value = 1, message = "quantity 至少为 1")
        int quantity) {
}
