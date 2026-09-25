package com.mars.cloud.service.gateway.web;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.mars.cloud.service.gateway.error.GatewayErrorCode;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * 把网关运行期抛出的异常解析成信封需要的状态与码。
 *
 * <p>映射规则（按判断顺序）：
 * <ol>
 *   <li>Sentinel 的拦截异常（{@link BlockException#isBlockException} 为真，含包装）：
 *       {@link GatewayErrorCode#RATE_LIMITED}，状态 429。</li>
 *   <li>{@link NotFoundException}：负载均衡找不到实例 → {@link GatewayErrorCode#UPSTREAM_NO_INSTANCE}。
 *       状态取异常自带的值（默认 503；开了 {@code loadbalancer.use404} 时是 404）。</li>
 *   <li>{@link ResponseStatusException} 且状态为 404：没有路由匹配 → {@link GatewayErrorCode#ROUTE_NOT_FOUND}。</li>
 *   <li>{@link ResponseStatusException} 且状态为 504：目标服务响应超时 → {@link GatewayErrorCode#UPSTREAM_TIMEOUT}。
 *       网关的路由过滤器已把自己的超时异常转成这个形态。</li>
 *   <li>其余 {@link ResponseStatusException}：与 Servlet 侧的兜底约定一致，码就是 HTTP 状态码本身。</li>
 *   <li>{@link ConnectException}（含 Netty 的连接超时子类）与 {@link UnknownHostException}（目标主机名解析失败）：
 *       {@link GatewayErrorCode#UPSTREAM_CONNECT_FAILED}。</li>
 *   <li>{@link TimeoutException}：{@link GatewayErrorCode#UPSTREAM_TIMEOUT}。</li>
 *   <li>其他任何异常：500，码为 {@code 500}。</li>
 * </ol>
 *
 * <p>本类不依赖 Spring 容器，便于逐条单测。
 */
public final class ErrorEnvelopeResolver {

    public ResolvedError resolve(Throwable ex) {
        if (BlockException.isBlockException(ex)) {
            return ResolvedError.of(GatewayErrorCode.RATE_LIMITED, "被 Sentinel 规则拦截：" + blockedBy(ex));
        }
        if (ex instanceof NotFoundException notFound) {
            return new ResolvedError(
                    notFound.getStatusCode(),
                    GatewayErrorCode.UPSTREAM_NO_INSTANCE.getCode(),
                    GatewayErrorCode.UPSTREAM_NO_INSTANCE.name(),
                    notFound.getReason());
        }
        if (ex instanceof ResponseStatusException statusException) {
            int status = statusException.getStatusCode().value();
            if (status == HttpStatus.NOT_FOUND.value()) {
                return ResolvedError.of(GatewayErrorCode.ROUTE_NOT_FOUND, statusException.getReason());
            }
            if (status == HttpStatus.GATEWAY_TIMEOUT.value()) {
                return ResolvedError.of(GatewayErrorCode.UPSTREAM_TIMEOUT, statusException.getReason());
            }
            return ResolvedError.ofStatus(statusException.getStatusCode(), statusException.getReason());
        }
        if (ex instanceof ConnectException || ex instanceof UnknownHostException) {
            return ResolvedError.of(GatewayErrorCode.UPSTREAM_CONNECT_FAILED, ex.getMessage());
        }
        if (ex instanceof TimeoutException) {
            return ResolvedError.of(GatewayErrorCode.UPSTREAM_TIMEOUT, ex.getMessage());
        }
        return ResolvedError.ofStatus(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    }

    /** 拦截异常的类名，例如 ParamFlowException；不回显规则内容。 */
    private static String blockedBy(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof BlockException) {
                return current.getClass().getSimpleName();
            }
        }
        return ex.getClass().getSimpleName();
    }
}
