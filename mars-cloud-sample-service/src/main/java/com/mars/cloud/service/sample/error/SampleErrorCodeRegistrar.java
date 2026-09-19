package com.mars.cloud.service.sample.error;

import com.mars.cloud.mvc.error.ErrorCodeRegistrar;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;

/**
 * 把本服务的错误码注册给框架。
 *
 * <p>注册之后，框架在启动时校验：码值是否越界、是否重复、区间是否与其他声明重叠。
 * 缺了这个 Bean，声明的区间就是空声明，校验器会报「错误码不在已声明的任何区间内」。
 */
@Component
public class SampleErrorCodeRegistrar implements ErrorCodeRegistrar {

    @Override
    public Collection<SampleErrorCode> codes() {
        return Arrays.asList(SampleErrorCode.values());
    }
}
