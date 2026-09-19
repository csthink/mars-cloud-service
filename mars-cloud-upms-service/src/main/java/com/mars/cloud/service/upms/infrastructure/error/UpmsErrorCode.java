package com.mars.cloud.service.upms.infrastructure.error;

import com.mars.cloud.common.error.ErrorCode;

/**
 * 本服务的错误码**数字**声明，用于启动期区间校验（越界 / 重复 / 段间重叠）。
 *
 * <p>注意它与 {@link UpmsProtocolCode} 是两套码，职责不同：
 * 本枚举只把数字交给校验器，**不参与响应体**；决策接口的响应体走
 * {@link UpmsProtocolCode} 的文本码（{@code missing_field} 等）。
 *
 * <p>因此对应的 {@code i18n/error-code*.properties} 只被启动校验读取，
 * **改那里不会改变线上文案**。
 */
public enum UpmsErrorCode implements ErrorCode {
    UPMS_PROTOCOL_ERROR(65001),
    UPMS_SNAPSHOT_UNAVAILABLE(65002),
    UPMS_INTERNAL_ERROR(65003);

    private final int code;

    UpmsErrorCode(int code) {
        this.code = code;
    }

    @Override
    public int getCode() {
        return code;
    }
}
