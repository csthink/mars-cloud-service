package com.mars.cloud.service.gateway.web;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Replaces the complete parsed admin IP policy after a valid configuration refresh. */
@Component
final class GatewayAdminIpAllowlist {
    static final String PROPERTY = "mars.gateway.admin.allowed-cidrs";
    private static final Logger log = LoggerFactory.getLogger(GatewayAdminIpAllowlist.class);
    private final Environment environment;
    private final AtomicReference<List<IpAddressPolicy.Subnet>> subnets;

    GatewayAdminIpAllowlist(Environment environment) {
        this.environment = environment;
        try {
            subnets = new AtomicReference<>(IpAddressPolicy.cidrs(environment.getProperty(PROPERTY, "")));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid admin allowed CIDRs", ex);
        }
    }

    boolean allows(String address) {
        if (address == null) return false;
        try {
            var client = IpAddressPolicy.literal(address);
            for (var subnet : subnets.get()) if (subnet.contains(client)) return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return false;
    }

    @EventListener
    void refresh(EnvironmentChangeEvent event) {
        if (!event.getKeys().contains(PROPERTY)) return;
        try {
            List<IpAddressPolicy.Subnet> replacement = IpAddressPolicy.cidrs(environment.getProperty(PROPERTY, ""));
            subnets.set(replacement);
        } catch (IllegalArgumentException ex) {
            log.warn("Rejected invalid admin IP allowlist update; previous policy remains active");
        }
    }
}
