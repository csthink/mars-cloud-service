package com.mars.cloud.service.upms.interfaces.dto;

import com.mars.cloud.service.upms.domain.decision.CanonicalValidator;
import com.mars.cloud.service.upms.domain.decision.CanonicalViolationException;
import com.mars.cloud.service.upms.domain.decision.MalformedResourceException;
import com.mars.cloud.service.upms.domain.decision.ResourceId;
import com.mars.cloud.service.upms.infrastructure.error.UimsProtocolCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class DecisionRequestParser {

    private static final Set<String> REQUIRED_FIELDS = Set.of("caller_id", "action", "resource");

    private final CanonicalValidator canonicalValidator;

    public DecisionRequestParser(CanonicalValidator canonicalValidator) {
        this.canonicalValidator = canonicalValidator;
    }

    public DecisionRequest parse(Map<String, Object> body) {
        if (body == null) {
            throw new ProtocolRequestException(UimsProtocolCode.MALFORMED_REQUEST, "request body must be a JSON object");
        }
        if (!body.keySet().equals(REQUIRED_FIELDS)) {
            for (String field : REQUIRED_FIELDS) {
                if (!body.containsKey(field)) {
                    throw new ProtocolRequestException(UimsProtocolCode.MISSING_FIELD, "missing field: " + field);
                }
            }
            throw new ProtocolRequestException(UimsProtocolCode.MALFORMED_REQUEST, "unexpected field in request body");
        }

        String callerId = requiredString(body, "caller_id");
        String action = requiredString(body, "action");
        String resource = requiredResourceString(body);
        return new DecisionRequest(callerId, action, resource);
    }

    private String requiredString(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (!(value instanceof String text)) {
            throw new ProtocolRequestException(UimsProtocolCode.MALFORMED_REQUEST, field + " must be a string");
        }
        if (text.isEmpty()) {
            throw new ProtocolRequestException(UimsProtocolCode.EMPTY_FIELD, field + " must be non-empty");
        }
        try {
            return canonicalValidator.requireCanonical(field, text);
        } catch (CanonicalViolationException ex) {
            throw new ProtocolRequestException(UimsProtocolCode.MALFORMED_REQUEST, field + " must be canonical");
        }
    }

    private String requiredResourceString(Map<String, Object> body) {
        Object value = body.get("resource");
        if (!(value instanceof String text)) {
            throw new ProtocolRequestException(UimsProtocolCode.MALFORMED_REQUEST, "resource must be a string");
        }
        if (text.isEmpty()) {
            throw new ProtocolRequestException(UimsProtocolCode.EMPTY_FIELD, "resource must be non-empty");
        }
        try {
            String canonical = canonicalValidator.requireCanonical("resource", text);
            ResourceId.parse(canonical, canonicalValidator);
            return canonical;
        } catch (MalformedResourceException | CanonicalViolationException ex) {
            throw new ProtocolRequestException(UimsProtocolCode.MALFORMED_RESOURCE, "resource must be platform-prefixed");
        }
    }
}
