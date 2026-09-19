package com.mars.cloud.service.upms.domain.decision;

import org.springframework.stereotype.Component;

@Component
public class CanonicalValidator {

    public String requireCanonical(String fieldName, String value) {
        if (value == null || value.isEmpty()) {
            throw new CanonicalViolationException(fieldName + " must be non-empty");
        }
        if (!value.equals(value.trim()) || containsControlCharacter(value)) {
            throw new CanonicalViolationException(fieldName + " must be canonical");
        }
        return value;
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
