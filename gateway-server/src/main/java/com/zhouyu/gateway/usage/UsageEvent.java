package com.zhouyu.gateway.usage;

/**
 * 用量事件。请求结束后发布到 MQ，由消费者异步落库。
 *
 * <p>字段设计原则：消费者无需回查任何在线状态即可完成落库。
 *
 * @param requestId     全局唯一，消费端幂等键
 * @param virtualModel  客户端请求的虚拟模型
 * @param upstreamModel 实际调用的上游模型
 * @param costMicro     本次成本（micro-USD）
 * @param usageSource   UPSTREAM（上游返回）或 ESTIMATED（网关估算）
 */
public record UsageEvent(
        String requestId,
        long tenantId,
        long apiKeyId,
        String virtualModel,
        String upstreamModel,
        long channelId,
        String channelName,
        String protocolIn,
        String protocolOut,
        boolean streaming,
        int statusCode,
        String errorCode,
        int inputTokens,
        int outputTokens,
        int cachedTokens,
        int reasoningTokens,
        String usageSource,
        long costMicro,
        int ttftMs,
        int totalMs,
        int retryCount,
        boolean stickyHit,
        String routeTrace,
        long createdAt
) {
}

