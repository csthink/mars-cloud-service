package com.mars.cloud.service.gateway.error;

import com.mars.cloud.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 网关层错误码。
 *
 * <p>区间 {@code 63000–63999} 来自框架的权威分配表（{@code FrameworkErrorCodeRange}，
 * 归属名 {@code gateway}）。分配表与它的启动期校验器都在 Servlet 栈的 mvc starter 里，
 * 响应式网关引不了，所以区间边界在这里用常量复述一遍，并由测试守住。
 *
 * <p>只有**网关自己产生**的错误用这里的码；业务服务返回的响应（含错误）原样透传，
 * 网关不改写、不重新包装。与 Servlet 侧的约定一致，无法归类的异常兜底用 HTTP 状态码本身作为码
 * （例如 {@code "500"}），不占用本区间。
 */
public enum GatewayErrorCode implements ErrorCode {

    /** 请求路径没有匹配任何路由（也覆盖网关自身端点不存在的情形）。 */
    ROUTE_NOT_FOUND(63001, HttpStatus.NOT_FOUND),

    /** 路由目标服务在注册中心里没有可用实例。 */
    UPSTREAM_NO_INSTANCE(63002, HttpStatus.SERVICE_UNAVAILABLE),

    /** 已选中实例，但建立连接失败（拒绝连接、连接超时等）。 */
    UPSTREAM_CONNECT_FAILED(63003, HttpStatus.BAD_GATEWAY),

    /** 连接已建立，但目标服务在规定时间内没有返回响应。 */
    UPSTREAM_TIMEOUT(63004, HttpStatus.GATEWAY_TIMEOUT),

    /** 撤销记录无法读取，且没有未过期的本地缓存。 */
    REVOCATION_STORE_UNAVAILABLE(63005, HttpStatus.SERVICE_UNAVAILABLE),

    /** 请求被 Sentinel 按路由、API 分组或客户端地址的限流规则拒绝。 */
    RATE_LIMITED(63006, HttpStatus.TOO_MANY_REQUESTS);

    /** 区间起点（含），与框架分配表的 {@code gateway} 段一致。 */
    public static final int RANGE_START = 63000;

    /** 区间终点（含），与框架分配表的 {@code gateway} 段一致。 */
    public static final int RANGE_END = 63999;

    private final int code;
    private final HttpStatus httpStatus;

    GatewayErrorCode(int code, HttpStatus httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    @Override
    public int getCode() {
        return code;
    }

    /** 该错误码对应的 HTTP 状态：网关自产错误的状态码由错误码唯一决定，不由调用方临时指定。 */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
