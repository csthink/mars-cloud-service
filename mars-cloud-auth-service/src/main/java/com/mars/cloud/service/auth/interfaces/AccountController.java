package com.mars.cloud.service.auth.interfaces;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {
    @GetMapping("/auth/v1/me")
    public Map<String,String> me(Authentication authentication) { return Map.of("userId",authentication.getName()); }
}
