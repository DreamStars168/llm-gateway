package com.zhouyu.gateway.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.zhouyu.gateway.common.ErrorCode;
import com.zhouyu.gateway.common.GatewayException;
import com.zhouyu.gateway.domain.ApiKeyView;
import com.zhouyu.gateway.routing.RouteTable;
import com.zhouyu.gateway.routing.RouteTableHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * API Key 鉴权。
 *
 * <p>本地 Caffeine 缓存 sha256 → 视图，避免每请求查库；命中时零网络往返。
 * 失效由 TTL 兜底（30s），后续接入 Redis 失效广播可缩短到秒级。
 */
@Component
public class ApiKeyAuthenticator {

    private static final String BEARER = "Bearer ";

    private final Cache<String, Optional<ApiKeyView>> cache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .recordStats()
            .build();

    private final RouteTableHolder tables;

    public ApiKeyAuthenticator(RouteTableHolder tables) {
        this.tables = tables;
    }

    /**
     * 从请求头提取并校验 Key。
     *
     * <p>支持 {@code Authorization: Bearer xxx}（OpenAI 风格）与
     * {@code x-api-key: xxx}（Anthropic 风格），前者优先。
     */
    public ApiKeyView authenticate(ServerHttpRequest request) {
        String secret = extractSecret(request);
        if (secret == null || secret.isBlank()) {
            throw new GatewayException(ErrorCode.INVALID_API_KEY, "缺少 API Key");
        }
        String hash = RouteTable.sha256(secret);
        // 缓存"查不到"的空结果，避免无效 Key 反复穿透
        Optional<ApiKeyView> cached = cache.get(hash, h -> tables.table().findKey(secret));
        return cached.orElseThrow(() -> new GatewayException(ErrorCode.INVALID_API_KEY, "API Key 无效"));
    }

    /** 主动失效某个 Key（管理端禁用/轮换时调用）。 */
    public void invalidate(String rawSecret) {
        cache.invalidate(RouteTable.sha256(rawSecret));
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    public CacheStats stats() {
        return cache.stats();
    }

    private static String extractSecret(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith(BEARER)) {
            return auth.substring(BEARER.length()).trim();
        }
        String apiKey = request.getHeaders().getFirst("x-api-key");
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        return null;
    }
}
