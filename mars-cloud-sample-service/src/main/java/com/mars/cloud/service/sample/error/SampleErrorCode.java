package com.mars.cloud.service.sample.error;

import com.mars.cloud.common.error.ErrorCode;

/**
 * 示例服务的错误码。
 *
 * <p>码值必须落在本服务声明的区间内（见 {@code config/application.yml} 的
 * {@code mars.error-code.ranges}），否则**启动期校验直接失败**——
 * 冲突在启动瞬间暴露，而不是等到某个分支被触发。
 *
 * <p>文案按 {@code error.code.<数字>} 的 key 从
 * {@code i18n/error-code*.properties} 取，无需在代码里写死。
 */
public enum SampleErrorCode implements ErrorCode {

    /** 资源不存在，映射为 HTTP 404。 */
    RESOURCE_NOT_FOUND(66101),

    /** 业务规则拒绝，映射为 HTTP 200 + {@code success:false}。 */
    OUT_OF_STOCK(66102),

    /** UPMS 返回失败信封、非预期状态或无效响应。 */
    UPMS_RESPONSE_INVALID(66103),

    /** UPMS 无实例、连接失败或超时。 */
    UPMS_UNAVAILABLE(66104);

    private final int code;

    SampleErrorCode(int code) {
        this.code = code;
    }

    @Override
    public int getCode() {
        return code;
    }
}
