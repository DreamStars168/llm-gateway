package com.zhouyu.gateway.usage;

/**
 * MQ 常量。
 */
public final class UsageMessaging {

    private UsageMessaging() {
    }

    public static final String EXCHANGE = "gateway.usage";
    public static final String QUEUE_PERSIST = "gateway.usage.persist";
    public static final String QUEUE_AGGREGATE = "gateway.usage.aggregate";
    public static final String QUEUE_BILLING = "gateway.usage.billing";
    public static final String ROUTING_KEY = "usage.record";
    public static final String DLX = "gateway.usage.dlx";
    public static final String QUEUE_DLQ = "gateway.usage.dlq";
}
