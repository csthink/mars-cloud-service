package com.mars.cloud.service.upms.infrastructure.error;

import com.mars.cloud.common.error.ErrorCode;
import com.mars.cloud.mvc.error.ErrorCodeRegistrar;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;

@Component
public class UpmsErrorCodeRegistrar implements ErrorCodeRegistrar {

    @Override
    public Collection<? extends ErrorCode> codes() {
        return java.util.stream.Stream.concat(Arrays.stream(UpmsErrorCode.values()),
                Arrays.stream(com.mars.cloud.security.SecurityErrorCode.values())).toList();
    }
}
