package com.mars.cloud.service.gateway.web;

import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Rejects ambiguous raw paths before Spring Security or Gateway decodes path segments. */
final class GatewayRawPathPolicy {
    private GatewayRawPathPolicy() { }

    static void validate(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return;
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (lower.contains("%2f") || lower.contains("%2e") || lower.contains("%5c")
                || rawPath.contains("//") || rawPath.indexOf(';') >= 0 || rawPath.indexOf('\\') >= 0) {
            throw invalid();
        }
        for (String segment : rawPath.split("/", -1)) {
            if (segment.equals("..") || segment.equals(".")) throw invalid();
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request path");
    }
}
