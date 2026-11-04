package com.zhouyu.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 网关配置。M1 阶段由 application.yml 驱动；后续替换为 MySQL 快照 + 热更新，
 * 只改 {@code GatewayConfigLoader} 的实现，本类结构不变。
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private String name = "llm-gateway";
    private int maxConcurrentStreams = 8000;
    private Duration upstreamFirstByteTimeout = Duration.ofSeconds(60);
    private Duration upstreamIdleTimeout = Duration.ofSeconds(120);
    private int retryMax = 2;
    private boolean quotaEnabled = true;

    private List<ChannelConfig> channels = new ArrayList<>();
    private List<RouteConfig> routes = new ArrayList<>();
    private List<KeyConfig> keys = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getMaxConcurrentStreams() { return maxConcurrentStreams; }
    public void setMaxConcurrentStreams(int v) { this.maxConcurrentStreams = v; }

    public Duration getUpstreamFirstByteTimeout() { return upstreamFirstByteTimeout; }
    public void setUpstreamFirstByteTimeout(Duration v) { this.upstreamFirstByteTimeout = v; }

    public Duration getUpstreamIdleTimeout() { return upstreamIdleTimeout; }
    public void setUpstreamIdleTimeout(Duration v) { this.upstreamIdleTimeout = v; }

    public int getRetryMax() { return retryMax; }
    public void setRetryMax(int v) { this.retryMax = v; }

    public boolean isQuotaEnabled() { return quotaEnabled; }
    public void setQuotaEnabled(boolean v) { this.quotaEnabled = v; }

    public List<ChannelConfig> getChannels() { return channels; }
    public void setChannels(List<ChannelConfig> v) { this.channels = v; }

    public List<RouteConfig> getRoutes() { return routes; }
    public void setRoutes(List<RouteConfig> v) { this.routes = v; }

    public List<KeyConfig> getKeys() { return keys; }
    public void setKeys(List<KeyConfig> v) { this.keys = v; }

    /** 渠道配置项。 */
    public static class ChannelConfig {
        private long id;
        private String name;
        private String protocol = "ANTHROPIC";
        private String baseUrl;
        private String apiKey;
        private int priority = 100;
        private int weight = 100;
        private int maxInflight = 200;
        private Duration timeout = Duration.ofSeconds(60);
        private boolean passthrough = true;
        private boolean enabled = true;
        private boolean apiKeyInQuery = false;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
        public int getWeight() { return weight; }
        public void setWeight(int weight) { this.weight = weight; }
        public int getMaxInflight() { return maxInflight; }
        public void setMaxInflight(int maxInflight) { this.maxInflight = maxInflight; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public boolean isPassthrough() { return passthrough; }
        public void setPassthrough(boolean passthrough) { this.passthrough = passthrough; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isApiKeyInQuery() { return apiKeyInQuery; }
        public void setApiKeyInQuery(boolean apiKeyInQuery) { this.apiKeyInQuery = apiKeyInQuery; }
    }

    /** 路由配置项。 */
    public static class RouteConfig {
        private String virtualModel;
        private long channelId;
        private String upstreamModel;
        private int priority = 100;
        private int weight = 100;
        private double priceRatio = 1.0;
        private boolean enabled = true;

        public String getVirtualModel() { return virtualModel; }
        public void setVirtualModel(String virtualModel) { this.virtualModel = virtualModel; }
        public long getChannelId() { return channelId; }
        public void setChannelId(long channelId) { this.channelId = channelId; }
        public String getUpstreamModel() { return upstreamModel; }
        public void setUpstreamModel(String upstreamModel) { this.upstreamModel = upstreamModel; }
        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
        public int getWeight() { return weight; }
        public void setWeight(int weight) { this.weight = weight; }
        public double getPriceRatio() { return priceRatio; }
        public void setPriceRatio(double priceRatio) { this.priceRatio = priceRatio; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** 客户端 Key 配置项。 */
    public static class KeyConfig {
        private long id;
        private String secret;
        private long tenantId;
        private long groupId = 1;
        private Long balanceMicro = 100_000_000L;
        private Integer rpmLimit;
        private List<String> allowedModels;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getTenantId() { return tenantId; }
        public void setTenantId(long tenantId) { this.tenantId = tenantId; }
        public long getGroupId() { return groupId; }
        public void setGroupId(long groupId) { this.groupId = groupId; }
        public Long getBalanceMicro() { return balanceMicro; }
        public void setBalanceMicro(Long balanceMicro) { this.balanceMicro = balanceMicro; }
        public Integer getRpmLimit() { return rpmLimit; }
        public void setRpmLimit(Integer rpmLimit) { this.rpmLimit = rpmLimit; }
        public List<String> getAllowedModels() { return allowedModels; }
        public void setAllowedModels(List<String> allowedModels) { this.allowedModels = allowedModels; }
    }
}

