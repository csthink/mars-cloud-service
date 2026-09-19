package com.mars.cloud.service.upms.domain.decision;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalAndResourceIdTest {

    private final CanonicalValidator validator = new CanonicalValidator();

    @Test
    void canonicalValueRejectsBlankWhitespaceAndControlCharactersWithoutNormalizing() {
        assertThatThrownBy(() -> validator.requireCanonical("caller_id", ""))
                .isInstanceOf(CanonicalViolationException.class)
                .hasMessageContaining("caller_id");
        assertThatThrownBy(() -> validator.requireCanonical("action", " write"))
                .isInstanceOf(CanonicalViolationException.class)
                .hasMessageContaining("action");
        assertThatThrownBy(() -> validator.requireCanonical("resource", "sinan:\nworkload"))
                .isInstanceOf(CanonicalViolationException.class)
                .hasMessageContaining("resource");
    }

    @Test
    void canonicalValueKeepsLiteralPatternCharacters() {
        assertThat(validator.requireCanonical("resource", "sinan:workload:*?"))
                .isEqualTo("sinan:workload:*?");
    }

    @Test
    void resourceIdParsesPlatformFromFirstColonOnly() {
        ResourceId resourceId = ResourceId.parse("opsdeck:view:domain:observability", validator);

        assertThat(resourceId.platform()).isEqualTo("opsdeck");
        assertThat(resourceId.localId()).isEqualTo("view:domain:observability");
        assertThat(resourceId.fullId()).isEqualTo("opsdeck:view:domain:observability");
    }

    @Test
    void resourceIdRejectsMissingPrefixEmptyPlatformAndEmptyLocalId() {
        assertThatThrownBy(() -> ResourceId.parse("missing-platform-prefix", validator))
                .isInstanceOf(MalformedResourceException.class);
        assertThatThrownBy(() -> ResourceId.parse(":local", validator))
                .isInstanceOf(MalformedResourceException.class);
        assertThatThrownBy(() -> ResourceId.parse("sinan:", validator))
                .isInstanceOf(MalformedResourceException.class);
    }
}
