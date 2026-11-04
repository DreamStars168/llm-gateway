package com.zhouyu.gateway.relay;

import com.zhouyu.gateway.domain.Channel;

import java.util.Map;

/**
 * 上游凭证与请求头的注入策略。
 *
 * <p>不同厂商的鉴权头完全不同，集中在这里，避免散落在适配器中。
 */
public final class UpstreamHeaders {

    private UpstreamHeaders() {
    }

    /**
     * 生成上游鉴权头。
     */
    public static Map<String, String> authHeaders(Channel channel) {
        return switch (channel.protocol()) {
            case ANTHROPIC -> Map.of(
                    "x-api-key", channel.apiKey(),
                    "anthropic-version", "2023-06-01");
            case OPENAI_CHAT, OPENAI_RESPONSES -> Map.of(
                    "Authorization", "Bearer " + channel.apiKey());
            case GEMINI -> Map.of(
                    "x-goog-api-key", channel.apiKey());
        };
    }

    /**
     * 可透传给上游的客户端请求头白名单。
     *
     * <p>只放行协议扩展头，不放行 Authorization / Host / Content-Length 等
     * 由 HTTP 客户端自行管理的头。
     */
    public static boolean isForwardable(String headerName) {
        String h = headerName.toLowerCase();
        return h.equals("anthropic-beta")
                || h.equals("anthropic-version")
                || h.equals("openai-beta")
                || h.equals("x-request-id")
                || h.equals("idempotency-key");
    }
}
