package com.mars.cloud.service.gateway.web;

import com.mars.cloud.service.gateway.error.GatewayErrorCode;
import io.netty.channel.ConnectTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorEnvelopeResolverTest {

    private final ErrorEnvelopeResolver resolver = new ErrorEnvelopeResolver();

    @Test
    void noInstanceMapsToUpstreamNoInstanceWithExceptionStatus() {
        ResolvedError resolved = resolver.resolve(NotFoundException.create(false, "Unable to find instance for x"));

        assertThat(resolved.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resolved.code()).isEqualTo(GatewayErrorCode.UPSTREAM_NO_INSTANCE.getCode());
        assertThat(resolved.detail()).contains("Unable to find instance");
        assertThat(resolved.isUnclassified()).as("目标不在是运行态事件，不按缺陷打堆栈").isFalse();
    }

    @Test
    void noInstanceKeepsFourOhFourWhenGatewayConfiguredSo() {
        ResolvedError resolved = resolver.resolve(NotFoundException.create(true, "Unable to find instance for x"));

        assertThat(resolved.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resolved.code()).isEqualTo(GatewayErrorCode.UPSTREAM_NO_INSTANCE.getCode());
    }

    @Test
    void unmatchedRouteMapsToRouteNotFound() {
        ResolvedError resolved = resolver.resolve(new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertThat(resolved.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resolved.code()).isEqualTo(GatewayErrorCode.ROUTE_NOT_FOUND.getCode());
    }

    @Test
    void gatewayTimeoutStatusMapsToUpstreamTimeout() {
        ResolvedError resolved = resolver.resolve(
                new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Response took longer than timeout"));

        assertThat(resolved.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(resolved.code()).isEqualTo(GatewayErrorCode.UPSTREAM_TIMEOUT.getCode());
    }

    @Test
    void otherStatusExceptionsUseHttpStatusAsCode() {
        ResolvedError resolved = resolver.resolve(new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE));

        assertThat(resolved.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(resolved.code()).isEqualTo(413);
        assertThat(resolved.fallbackMessage()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.getReasonPhrase());
    }

    @Test
    void connectFailuresMapToBadGateway() {
        assertThat(resolver.resolve(new ConnectException("Connection refused")).code())
                .isEqualTo(GatewayErrorCode.UPSTREAM_CONNECT_FAILED.getCode());
        assertThat(resolver.resolve(new ConnectTimeoutException("connect timed out")).status())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(resolver.resolve(new UnknownHostException("Failed to resolve 'upms.internal'")).code())
                .isEqualTo(GatewayErrorCode.UPSTREAM_CONNECT_FAILED.getCode());
    }

    @Test
    void plainTimeoutMapsToUpstreamTimeout() {
        ResolvedError resolved = resolver.resolve(new TimeoutException("Did not observe any item"));

        assertThat(resolved.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(resolved.code()).isEqualTo(GatewayErrorCode.UPSTREAM_TIMEOUT.getCode());
    }

    @Test
    void anythingElseIsInternalServerErrorWithStatusAsCode() {
        ResolvedError resolved = resolver.resolve(new IllegalStateException("boom"));

        assertThat(resolved.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resolved.code()).isEqualTo(500);
        assertThat(resolved.isUnclassified()).isTrue();
        assertThat(resolved.detail()).isEqualTo("boom");
    }
}
