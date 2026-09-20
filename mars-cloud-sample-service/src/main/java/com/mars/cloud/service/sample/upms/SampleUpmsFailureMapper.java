package com.mars.cloud.service.sample.upms;

import com.mars.cloud.feign.DownstreamFailure;
import com.mars.cloud.feign.DownstreamFailureKind;
import com.mars.cloud.feign.DownstreamFailureMapper;
import com.mars.cloud.mvc.exception.HttpException;
import com.mars.cloud.service.sample.error.SampleErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 把 UPMS 失败翻译为 sample 自己的稳定错误码，不透传下游 message。
 */
@Component
public final class SampleUpmsFailureMapper implements DownstreamFailureMapper {

    @Override
    public String clientName() {
        return UpmsDecisionClient.CLIENT_NAME;
    }

    @Override
    public RuntimeException map(DownstreamFailure failure) {
        if (failure.kind() == DownstreamFailureKind.UNAVAILABLE
                || failure.kind() == DownstreamFailureKind.TIMEOUT) {
            return new HttpException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    SampleErrorCode.UPMS_UNAVAILABLE,
                    null,
                    null,
                    failure.cause());
        }
        return new HttpException(
                HttpStatus.BAD_GATEWAY.value(),
                SampleErrorCode.UPMS_RESPONSE_INVALID,
                null,
                null,
                failure.cause());
    }
}
