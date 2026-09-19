package com.mars.cloud.service.upms.client;

import com.mars.cloud.service.upms.application.dto.ConsumerDecisionClassification;
import com.mars.cloud.service.upms.application.service.ConsumerResponseClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class HalfBadResponseClassifierTest {

    private final ConsumerResponseClassifier classifier = new ConsumerResponseClassifier();

    @Test
    void validAllowAndDenyResponsesAreTheOnlyDecisionShapes() {
        assertThat(classifier.classify(200, """
                {"success":true,"result":{"decision":"allow","reason_code":"granted","decision_id":"d1"}}
                """))
                .isEqualTo(ConsumerDecisionClassification.decision("allow"));

        assertThat(classifier.classify(200, """
                {"success":true,"result":{"decision":"deny","reason_code":"no_grant","decision_id":"d2"}}
                """))
                .isEqualTo(ConsumerDecisionClassification.decision("deny"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halfBadResponses")
    void halfBadResponsesNeverProduceDecision(String label, int status, String body) {
        ConsumerDecisionClassification classification = classifier.classify(status, body);

        assertThat(classification.type())
                .as(label)
                .isEqualTo(ConsumerDecisionClassification.Type.PROTOCOL_ERROR);
        assertThat(classification.decision()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("serviceFailureResponses")
    void serviceFailureResponsesDoNotProduceDecision(String label, int status, String body) {
        ConsumerDecisionClassification classification = classifier.classify(status, body);

        assertThat(classification.type())
                .as(label)
                .isEqualTo(ConsumerDecisionClassification.Type.SERVICE_FAILURE);
        assertThat(classification.decision()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("integrationBugResponses")
    void integrationBugResponsesDoNotProduceDecision(String label, int status, String body) {
        ConsumerDecisionClassification classification = classifier.classify(status, body);

        assertThat(classification.type())
                .as(label)
                .isEqualTo(ConsumerDecisionClassification.Type.INTEGRATION_BUG);
        assertThat(classification.decision()).isNull();
    }

    @Test
    void transportFailureClassifiesAsServiceFailureWithoutDecision() {
        ConsumerDecisionClassification classification = classifier.transportFailure();

        assertThat(classification.type()).isEqualTo(ConsumerDecisionClassification.Type.SERVICE_FAILURE);
        assertThat(classification.decision()).isNull();
    }

    private static Stream<Arguments> halfBadResponses() {
        return Stream.of(
                Arguments.of("200 success false", 200, """
                        {"success":false,"code":"snapshot_unavailable"}
                        """),
                Arguments.of("200 success true without result.decision", 200, """
                        {"success":true,"result":{"reason_code":"granted","decision_id":"d1"}}
                        """),
                Arguments.of("200 success true with non-null code", 200, """
                        {"success":true,"code":"snapshot_unavailable","result":{"decision":"allow"}}
                        """),
                Arguments.of("non-200 success true", 503, """
                        {"success":true,"result":{"decision":"deny"}}
                        """),
                Arguments.of("error response missing code", 503, """
                        {"success":false}
                        """),
                Arguments.of("error response carrying result.decision", 500, """
                        {"success":false,"code":"internal_error","result":{"decision":"deny"}}
                        """),
                Arguments.of("2xx illegal body", 204, ""),
                Arguments.of("200 empty body", 200, ""),
                Arguments.of("non-json body", 200, "not-json"),
                Arguments.of("gateway response", 502, """
                        {"success":false,"code":"snapshot_unavailable"}
                        """),
                Arguments.of("gateway timeout response", 504, """
                        {"success":false,"code":"snapshot_unavailable"}
                        """),
                Arguments.of("rate limited response", 429, """
                        {"success":false,"code":"internal_error"}
                        """),
                Arguments.of("request timeout response", 408, """
                        {"success":false,"code":"internal_error"}
                        """),
                Arguments.of("400 auth code status mismatch", 400, """
                        {"success":false,"code":"invalid_token"}
                        """),
                Arguments.of("401 invalid request code status mismatch", 401, """
                        {"success":false,"code":"missing_field"}
                        """),
                Arguments.of("500 snapshot code status mismatch", 500, """
                        {"success":false,"code":"snapshot_unavailable"}
                        """),
                Arguments.of("503 internal code status mismatch", 503, """
                        {"success":false,"code":"internal_error"}
                        """)
        );
    }

    private static Stream<Arguments> serviceFailureResponses() {
        return Stream.of(
                Arguments.of("snapshot unavailable", 503, """
                        {"success":false,"code":"snapshot_unavailable"}
                        """),
                Arguments.of("internal error", 500, """
                        {"success":false,"code":"internal_error"}
                        """)
        );
    }

    private static Stream<Arguments> integrationBugResponses() {
        return Stream.of(
                Arguments.of("missing field", 400, """
                        {"success":false,"code":"missing_field"}
                        """),
                Arguments.of("malformed resource", 400, """
                        {"success":false,"code":"malformed_resource"}
                        """),
                Arguments.of("invalid token", 401, """
                        {"success":false,"code":"invalid_token"}
                        """),
                Arguments.of("caller token mismatch", 401, """
                        {"success":false,"code":"caller_token_mismatch"}
                        """)
        );
    }
}
