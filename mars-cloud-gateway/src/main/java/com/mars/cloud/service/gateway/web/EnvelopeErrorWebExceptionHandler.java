package com.mars.cloud.service.gateway.web;

import com.mars.cloud.common.response.UnifyResponse;
import com.mars.cloud.service.gateway.env.EnvProfilesProperties;
import com.mars.cloud.service.gateway.error.GatewayErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.context.MessageSource;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 响应式栈的全局异常处理：把网关自身产生的错误写成与业务服务同一种信封。
 *
 * <p>信封 {@link UnifyResponse} 来自 {@code mars-cloud-common}，与 Servlet 栈 mvc starter 的
 * {@code GlobalExceptionAdvice} 共用同一个类型；这里只负责把异常翻成「状态 + 码 + 文案」，
 * 规则见 {@link ErrorEnvelopeResolver}。文案先查 i18n（{@code error.code.<码>}，随请求的
 * {@code Accept-Language} 变化），未命中时回退到解析结果自带的兜底文案。
 *
 * <p>开发环境（{@code mars.env.dev-profiles} 命中）下 {@code result} 回带调试详情；其他环境下
 * {@code result} 为空，避免把内部信息带给外部调用方。这条与 Servlet 侧一致。
 *
 * <p>只处理**网关自己抛出**的异常。业务服务返回的响应不经过这里，网关原样透传。
 */
public class EnvelopeErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeErrorWebExceptionHandler.class);

    private final ErrorEnvelopeResolver resolver;
    private final MessageSource messageSource;
    private final Environment environment;
    private final EnvProfilesProperties envProfiles;
    private final ServerResponse.Context responseContext;

    public EnvelopeErrorWebExceptionHandler(ErrorEnvelopeResolver resolver,
                                            MessageSource messageSource,
                                            Environment environment,
                                            EnvProfilesProperties envProfiles,
                                            ServerCodecConfigurer codecConfigurer) {
        this.resolver = resolver;
        this.messageSource = messageSource;
        this.environment = environment;
        this.envProfiles = envProfiles;
        this.responseContext = new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return codecConfigurer.getWriters();
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return List.of();
            }
        };
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            // 响应头已经发出去（例如目标服务已开始回包），此时无法再改写成信封，交回给框架。
            return Mono.error(ex);
        }

        ServerHttpRequest request = exchange.getRequest();
        ResolvedError resolved = resolver.resolve(ex);
        logResolved(request, resolved, ex);

        UnifyResponse<Object> body = UnifyResponse.fail(resolved.code(), message(resolved, locale(exchange)));
        if (envProfiles.isDev(environment.getActiveProfiles())) {
            body.setResult(debugDetails(request, resolved));
        }

        return ServerResponse.status(resolved.status())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .flatMap(response -> response.writeTo(exchange, responseContext));
    }

    private void logResolved(ServerHttpRequest request, ResolvedError resolved, Throwable ex) {
        String where = request.getMethod() + " " + request.getURI().getPath();
        if (resolved.code() == GatewayErrorCode.RATE_LIMITED.getCode()) {
            // 限流在受攻击时成批出现，逐条 WARN 会淹没日志；次数由指标 mars.sentinel.requests.blocked 记录。
            // 网关接入 Spring Security 之前管理端点只暴露 health 与 info，这个指标在那之前读不到（README「限流」）
            log.debug("网关限流 {} code={} {}: {}", resolved.status().value(), resolved.code(), where, resolved.detail());
            return;
        }
        if (resolved.isUnclassified()) {
            log.error("网关错误 {} code={} {}", resolved.status().value(), resolved.code(), where, ex);
        } else {
            log.warn("网关错误 {} code={} {}: {}", resolved.status().value(), resolved.code(), where, resolved.detail());
        }
    }

    /**
     * i18n 主通道 {@code error.code.<码>}；未命中时用解析结果自带的兜底文案。
     */
    private String message(ResolvedError resolved, Locale locale) {
        String resolvedMessage = messageSource.getMessage("error.code." + resolved.code(), null, null, locale);
        if (resolvedMessage != null && !resolvedMessage.isEmpty()) {
            return resolvedMessage;
        }
        return resolved.fallbackMessage();
    }

    private static Locale locale(ServerWebExchange exchange) {
        Locale locale = exchange.getLocaleContext().getLocale();
        return locale == null ? Locale.getDefault() : locale;
    }

    /**
     * 与 Servlet 侧 {@code GlobalExceptionAdvice} 的调试详情同一组键：detail / ip / method / uri / headers。
     */
    private static Map<String, Object> debugDetails(ServerHttpRequest request, ResolvedError resolved) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("detail", resolved.detail());
        InetSocketAddress remote = request.getRemoteAddress();
        details.put("ip", remote == null ? null : remote.getAddress().getHostAddress());
        details.put("method", request.getMethod().name());
        details.put("uri", request.getURI().getRawPath());
        Map<String, String> headers = new LinkedHashMap<>();
        request.getHeaders().forEach((name, values) -> {
            if (!com.mars.cloud.common.http.SensitiveHttpHeaders.isSensitive(name)) {
                headers.put(name, String.join(",", values));
            }
        });
        details.put("headers", headers);
        return details;
    }
}
