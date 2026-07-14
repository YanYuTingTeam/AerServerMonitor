package com.aermini.servermonitor.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

public class IpUtil {

    public static boolean matchesCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            String network = parts[0];
            int prefix = Integer.parseInt(parts[1]);

            byte[] ipBytes = InetAddress.getByName(ip).getAddress();
            byte[] netBytes = InetAddress.getByName(network).getAddress();

            if (ipBytes.length != netBytes.length) return false;

            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (ipBytes[i] != netBytes[i]) return false;
            }
            if (remainingBits > 0 && fullBytes < ipBytes.length) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((ipBytes[fullBytes] & mask) != (netBytes[fullBytes] & mask)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean matchesIpOrRange(String ip, String pattern) {
        pattern = pattern.trim();
        if (pattern.contains("/")) {
            return matchesCidr(ip, pattern);
        }
        return ip.equals(pattern);
    }

    public static boolean isInRangeList(String ip, List<String> ranges) {
        for (String range : ranges) {
            if (matchesIpOrRange(ip, range)) return true;
        }
        return false;
    }

    public static String toCidr24(String ip) {
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            if (bytes.length == 4) {
                return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "." + (bytes[2] & 0xFF) + ".0/24";
            }
            return ip;
        } catch (UnknownHostException e) {
            return ip;
        }
    }

    public static String toCidr16(String ip) {
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            if (bytes.length == 4) {
                return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + ".0.0/16";
            }
            return ip;
        } catch (UnknownHostException e) {
            return ip;
        }
    }

    public static String normalizeBanTarget(String ip, String scope) {
        switch (scope.toLowerCase()) {
            case "ip-24": return toCidr24(ip);
            case "ip-16": return toCidr16(ip);
            default: return ip;
        }
    }
}
