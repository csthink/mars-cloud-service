package com.mars.cloud.service.gateway.web;

import java.util.ArrayList;
import java.util.Locale;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/** 在 Gateway 自带的出站清理器之后写入入口已核对的规范转发头。 */
@Component
public final class GatewayCanonicalHeadersFilter implements HttpHeadersFilter, Ordered {
    @Override
    public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
        GatewayIngressFilter.CanonicalHeaders canonical = exchange.getAttribute(GatewayIngressFilter.CANONICAL_HEADERS_ATTRIBUTE);
        if (canonical == null) throw new IllegalStateException("Gateway ingress did not provide canonical forwarding headers");
        HttpHeaders output = new HttpHeaders();
        input.forEach(output::addAll);
        for (String name : new ArrayList<>(output.headerNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("forwarded") || lower.startsWith("x-forwarded-")) output.remove(name);
        }
        output.set("X-Forwarded-For", canonical.clientIp());
        output.set("X-Forwarded-Host", canonical.host());
        output.set("X-Forwarded-Proto", canonical.scheme());
        output.set("X-Forwarded-Port", canonical.port());
        return output;
    }

    @Override public int getOrder() { return 1; }
}
