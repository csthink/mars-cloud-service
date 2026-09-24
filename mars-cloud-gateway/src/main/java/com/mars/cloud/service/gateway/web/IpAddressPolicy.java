package com.mars.cloud.service.gateway.web;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** 仅接受 IP 字面量和明确的 CIDR，不触发主机名解析。 */
final class IpAddressPolicy {
    private IpAddressPolicy() { }

    static InetAddress literal(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim()) || value.indexOf('%') >= 0
                || value.indexOf('[') >= 0 || value.indexOf(']') >= 0) {
            throw new IllegalArgumentException("Invalid IP literal");
        }
        if (value.indexOf(':') < 0) {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4 literal");
            for (String part : parts) {
                if (!part.matches("(?:0|[1-9][0-9]{0,2})") || Integer.parseInt(part) > 255)
                    throw new IllegalArgumentException("Invalid IPv4 literal");
            }
        } else if (!value.matches("[0-9A-Fa-f:.]+")) {
            throw new IllegalArgumentException("Invalid IPv6 literal");
        }
        return InetAddress.ofLiteral(value);
    }

    static List<Subnet> cidrs(String config) {
        if (config == null || config.isBlank()) return List.of();
        List<Subnet> result = new ArrayList<>();
        for (String entry : config.split(",", -1)) {
            String[] parts = entry.trim().split("/", -1);
            if (parts.length > 2) throw new IllegalArgumentException("Invalid CIDR");
            InetAddress address = literal(parts[0]);
            int bits = address.getAddress().length * 8;
            int prefix;
            try { prefix = parts.length == 1 ? bits : Integer.parseInt(parts[1]); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid CIDR prefix", ex); }
            if (prefix < 0 || prefix > bits) throw new IllegalArgumentException("Invalid CIDR prefix");
            result.add(new Subnet(address.getAddress(), prefix));
        }
        return List.copyOf(result);
    }

    record Subnet(byte[] network, int prefix) {
        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) return false;
            int full = prefix / 8;
            for (int i = 0; i < full; i++) if (candidate[i] != network[i]) return false;
            int rest = prefix % 8;
            return rest == 0 || ((candidate[full] ^ network[full]) & (0xff << (8 - rest))) == 0;
        }
    }
}
