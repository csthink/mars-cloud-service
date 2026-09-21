package com.mars.cloud.service.sample.security;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/** Supplies a valid identity for protocol tests; authentication rejection is tested separately. */
public final class AuthenticatedProtocolRequests {
    private AuthenticatedProtocolRequests() { }
    public static RequestPostProcessor identity() {
        return request -> {
            String subject = "caller-allow";
            try {
                var value = JsonMapper.builder().build().readTree(request.getContentAsByteArray()).path("caller_id");
                if (value.isString() && !value.stringValue().isBlank() && value.stringValue().equals(value.stringValue().strip()))
                    subject = value.stringValue();
            } catch (RuntimeException ignored) {
                // Malformed protocol bodies still need a valid independent authentication input.
            }
            request.addHeader("Authorization", "Bearer " + SecurityTestSupport.token(subject));
            return request;
        };
    }
    public static MockHttpServletRequestBuilder get(String path) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).with(identity());
    }
    public static MockHttpServletRequestBuilder post(String path) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).with(identity());
    }
}
