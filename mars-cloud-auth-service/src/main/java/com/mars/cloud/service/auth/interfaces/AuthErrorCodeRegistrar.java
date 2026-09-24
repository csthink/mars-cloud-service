package com.mars.cloud.service.auth.interfaces;

import com.mars.cloud.mvc.error.ErrorCodeRegistrar;
import com.mars.cloud.common.error.ErrorCode;
import com.mars.cloud.security.SecurityErrorCode;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class AuthErrorCodeRegistrar implements ErrorCodeRegistrar {
    @Override public Collection<? extends ErrorCode> codes() {
        return Stream.concat(Arrays.stream(AuthErrorCode.values()),Arrays.stream(SecurityErrorCode.values())).toList();
    }
}
