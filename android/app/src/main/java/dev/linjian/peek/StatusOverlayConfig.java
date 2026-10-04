package dev.linjian.peek;

import java.net.URI;

/** Pure Java validation also exercised without an Android device. Never log the secret URL. */
final class StatusOverlayConfig {
    static final String ENABLED = "status_overlay_enabled";
    static final String ADDRESS = "status_overlay_mcp_address";
    static final String CACHE = "status_overlay_cache";
    static final String CACHE_TIME = "status_overlay_cache_time";
    static final String X = "status_overlay_x", Y = "status_overlay_y";
    static final String COLLAPSED = "status_overlay_collapsed";

    final String address, endpoint, token;

    StatusOverlayConfig(String input) {
        try {
            URI uri = new URI(input.trim().replaceAll("/+$", ""));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0
                    || !uri.getRawPath().matches("/mcp/[A-Za-z0-9_-]{16,128}")) {
                throw new IllegalArgumentException();
            }
            address = uri.toASCIIString();
            endpoint = address + "/status";
            token = uri.getRawPath().substring("/mcp/".length());
        } catch (Exception e) {
            throw new IllegalArgumentException("请粘贴完整 HTTPS MCP 地址，格式：https://你的域名/mcp/私密密钥");
        }
    }

    static long retryDelay(int failures) {
        return Math.min(60000L, 5000L * (1L << Math.min(Math.max(0, failures), 4)));
    }
}
