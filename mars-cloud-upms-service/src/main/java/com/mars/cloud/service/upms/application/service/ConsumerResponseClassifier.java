package com.mars.cloud.service.upms.application.service;

import com.mars.cloud.service.upms.application.dto.ConsumerDecisionClassification;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

@Component
public class ConsumerResponseClassifier {

    private static final Set<String> INVALID_REQUEST_CODES = Set.of(
            "missing_field",
            "empty_field",
            "malformed_request",
            "malformed_resource"
    );
    private static final Set<String> AUTH_BUG_CODES = Set.of(
            "invalid_token",
            "caller_token_mismatch"
    );

    private final ObjectMapper objectMapper;

    public ConsumerResponseClassifier() {
        this(JsonMapper.builder().build());
    }

    public ConsumerResponseClassifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ConsumerDecisionClassification classify(int httpStatus, String body) {
        JsonNode envelope = parseBody(body);
        if (envelope == null) {
            return ConsumerDecisionClassification.protocolError();
        }
        if (httpStatus == 200) {
            return classifyDecision(envelope);
        }
        if (httpStatus == 400 || httpStatus == 401 || httpStatus == 500 || httpStatus == 503) {
            return classifyError(httpStatus, envelope);
        }
        return ConsumerDecisionClassification.protocolError();
    }

    public ConsumerDecisionClassification transportFailure() {
        return ConsumerDecisionClassification.serviceFailure("transport_failure");
    }

    private ConsumerDecisionClassification classifyDecision(JsonNode envelope) {
        if (!booleanField(envelope, "success", true)) {
            return ConsumerDecisionClassification.protocolError();
        }
        if (hasNonNullField(envelope, "code")) {
            return ConsumerDecisionClassification.protocolError();
        }
        JsonNode result = envelope.get("result");
        if (result == null || !result.isObject()) {
            return ConsumerDecisionClassification.protocolError();
        }
        JsonNode decision = result.get("decision");
        if (decision == null || !decision.isTextual()) {
            return ConsumerDecisionClassification.protocolError();
        }
        String value = decision.asText();
        if (!value.equals("allow") && !value.equals("deny")) {
            return ConsumerDecisionClassification.protocolError();
        }
        return ConsumerDecisionClassification.decision(value);
    }

    private ConsumerDecisionClassification classifyError(int httpStatus, JsonNode envelope) {
        if (!booleanField(envelope, "success", false)) {
            return ConsumerDecisionClassification.protocolError();
        }
        JsonNode code = envelope.get("code");
        if (code == null || !code.isTextual() || code.asText().isEmpty()) {
            return ConsumerDecisionClassification.protocolError();
        }
        if (errorBodyCarriesDecision(envelope)) {
            return ConsumerDecisionClassification.protocolError();
        }
        String value = code.asText();
        if (httpStatus == 400 && INVALID_REQUEST_CODES.contains(value)) {
            return ConsumerDecisionClassification.integrationBug(value);
        }
        if (httpStatus == 401 && AUTH_BUG_CODES.contains(value)) {
            return ConsumerDecisionClassification.integrationBug(value);
        }
        if (httpStatus == 503 && value.equals("snapshot_unavailable")) {
            return ConsumerDecisionClassification.serviceFailure(value);
        }
        if (httpStatus == 500 && value.equals("internal_error")) {
            return ConsumerDecisionClassification.serviceFailure(value);
        }
        return ConsumerDecisionClassification.protocolError();
    }

    private JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode parsed = objectMapper.readTree(body);
            return parsed.isObject() ? parsed : null;
        } catch (JacksonException ex) {
            return null;
        }
    }

    private static boolean booleanField(JsonNode node, String field, boolean expected) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() && value.asBoolean() == expected;
    }

    private static boolean hasNonNullField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull();
    }

    private static boolean errorBodyCarriesDecision(JsonNode envelope) {
        JsonNode result = envelope.get("result");
        return result != null && result.isObject() && result.has("decision");
    }
}
