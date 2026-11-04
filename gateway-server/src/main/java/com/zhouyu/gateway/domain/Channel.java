package com.zhouyu.gateway.domain;

import java.time.Duration;

/**
 * 上游渠道（不可变快照对象）。
 *
 * @param id          渠道 ID
 * @param name        渠道名，用于日志与响应头
 * @param protocol    出口协议
 * @param baseUrl     上游根地址，如 https://api.anthropic.com
 * @param apiKey      上游凭证（内存中为明文，落库时加密）
 * @param priority    越小越优先，硬分层
 * @param weight      层内权重
 * @param maxInflight 最大并发（本地信号量）
 * @param timeout     首字节超时
 * @param passthrough 是否允许原生透传
 * @param enabled     是否启用
 * @param apiKeyInQuery 是否用 query 传凭证（Gemini 部分兼容端点需要）
 */
public record Channel(
        long id,
        String name,
        Protocol protocol,
        String baseUrl,
        String apiKey,
        int priority,
        int weight,
        int maxInflight,
        Duration timeout,
        boolean passthrough,
        boolean enabled,
        boolean apiKeyInQuery
) {

    /** 兼容旧构造：默认 header 传凭证。 */
    public Channel(long id, String name, Protocol protocol, String baseUrl, String apiKey,
                   int priority, int weight, int maxInflight, Duration timeout,
                   boolean passthrough, boolean enabled) {
        this(id, name, protocol, baseUrl, apiKey, priority, weight, maxInflight,
                timeout, passthrough, enabled, false);
    }

    /**
     * 拼接上游完整地址，避免出现双斜杠。
     */
    public String resolve(String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String p = path.startsWith("/") ? path : "/" + path;
        return base + p;
    }

    /**
     * 是否用 query 参数传递凭证。
     */
    public boolean apiKeyQueryStyle() {
        return apiKeyInQuery;
    }
}
