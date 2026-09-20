package com.mars.cloud.service.gateway.env;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 哪些 profile 算「开发/测试环境」。
 *
 * <p>配置键 {@code mars.env.dev-profiles} 与默认值都与 Servlet 栈 mvc starter 的同名类保持一致，
 * 这样同一份部署配置在网关与业务服务上语义相同：命中开发环境时，失败响应的 {@code result}
 * 回带调试详情（异常详情、请求 IP、URI、请求头）；否则 {@code result} 为空。
 *
 * <p>mvc starter 的那份实现依赖 Servlet 栈，网关不能引用，所以这里是第二份。
 */
@ConfigurationProperties(prefix = "mars.env")
public class EnvProfilesProperties {

    /**
     * 认为是「开发/测试环境」的 profile 列表（大小写不敏感）。
     */
    private List<String> devProfiles = new ArrayList<>(Arrays.asList("local", "dev", "test", "testing"));

    public List<String> getDevProfiles() {
        return devProfiles;
    }

    public void setDevProfiles(List<String> devProfiles) {
        this.devProfiles = devProfiles;
    }

    /**
     * 判断激活的 profile 是否命中开发环境列表（忽略大小写）。
     */
    public boolean isDev(String[] activeProfiles) {
        if (activeProfiles == null || activeProfiles.length == 0) {
            return false;
        }
        for (String active : activeProfiles) {
            if (active == null) {
                continue;
            }
            for (String dev : devProfiles) {
                if (dev != null && dev.equalsIgnoreCase(active)) {
                    return true;
                }
            }
        }
        return false;
    }
}
