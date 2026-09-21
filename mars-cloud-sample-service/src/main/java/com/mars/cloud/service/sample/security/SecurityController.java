package com.mars.cloud.service.sample.security;

import com.mars.cloud.common.context.CallerContext;
import com.mars.cloud.security.AuthenticatedCaller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Demonstrates verified identity and service-declared method authorization. */
@RestController
@RequestMapping("/v1/security")
public class SecurityController {
    @GetMapping("/me")
    public CallerContext me(Authentication authentication) { return AuthenticatedCaller.from(authentication); }
    @GetMapping("/decision")
    @PreAuthorize("@marsAuthorization.allowed('view','demo:view:domain:kubernetes-ops')")
    public java.util.Map<String, Boolean> decision() { return java.util.Map.of("allowed", true); }
}
