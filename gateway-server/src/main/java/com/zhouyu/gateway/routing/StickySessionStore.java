package com.zhouyu.gateway.routing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zhouyu.gateway.routing.RouteTable.Candidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

/**
 * 会话亲和存储（Sticky Session）。
 *
 * <p><b>为什么需要</b>：多账号/多渠道时，若同一会话被轮流分配到不同上游，
 * 上游的 prompt cache 会全部失效（各家缓存按账号隔离），成本可能翻 5~10 倍。
 * 亲和把同一会话钉在同一个渠道上，既均衡负载又保住缓存。
 *
 * <h3>两级结构</h3>
 * <ol>
 *   <li><b>本地 Caffeine</b>：命中时零网络往返（绝大部分请求走这里）</li>
 *   <li><b>Redis</b>：跨实例共享，保证多节点下同一会话落到同一渠道</li>
 * </ol>
 *
 * <p>写路径：本地写入后异步写 Redis（fire-and-forget），不阻塞请求。
 * 读路径：本地未命中才查 Redis，仍未命中则返回空由调用方重新选择。
 *
 * <h3>失效</h3>
 * 若映射指向的渠道已不可用（熔断/禁用），调用方会重新选择并覆盖映射，
 * 同时记录 {@code sticky_rebind} 指标——这个指标上升说明渠道不稳定。
 */
@Component
public class StickySessionStore {

    private static final Logger log = LoggerFactory.getLogger(StickySessionStore.class);

    private final ReactiveStringRedisTemplate redis;

    /** 本地一级缓存。TTL 短于 Redis，保证跨实例不一致的时间窗口有限。 */
    private final Cache<String, String> local;

    private final Duration redisTtl;

    public StickySessionStore(ReactiveStringRedisTemplate redis,
                              @org.springframework.beans.factory.annotation.Value("${gateway.sticky.ttl-seconds:1800}") long ttlSeconds,
                              @org.springframework.beans.factory.annotation.Value("${gateway.sticky.local-ttl-seconds:60}") long localTtlSeconds) {
        this.redis = redis;
        this.redisTtl = Duration.ofSeconds(ttlSeconds);
        this.local = Caffeine.newBuilder()
                .maximumSize(200_000)
                .expireAfterAccess(Duration.ofSeconds(localTtlSeconds))
                .build();
    }

    /** 映射键：租户 + 虚拟模型 + 会话，避免不同租户互相干扰。 */
    private static String cacheKey(long tenantId, String virtualModel, String sessionKey) {
        return tenantId + "|" + virtualModel + "|" + sessionKey;
    }

    private static String redisKey(long tenantId, String virtualModel, String sessionKey) {
        return "sticky:" + tenantId + ":" + virtualModel + ":" + sessionKey;
    }

    /**
     * 同步读本地缓存（不阻塞、不打 Redis）。
     *
     * <p>用于请求关键路径：本地命中是最常见情况，未命中时直接退化轮询，
     * 由 {@link #bind} 在请求结束后补写。这样关键路径上<b>没有任何 Redis 往返</b>。
     * 代价是跨实例场景下新会话的首个请求可能落到别的渠道——可接受（只影响首轮缓存）。
     */
    public Long peekLocal(long tenantId, String virtualModel, String sessionKey) {
        if (sessionKey == null) {
            return null;
        }
        String v = local.getIfPresent(cacheKey(tenantId, virtualModel, sessionKey));
        return v == null ? null : Long.parseLong(v);
    }

    /**
     * 查询该会话已绑定的渠道。返回空表示未绑定或已失效。
     */
    public Mono<Optional<Long>> lookup(long tenantId, String virtualModel, String sessionKey) {
        if (sessionKey == null) {
            return Mono.just(Optional.empty());
        }
        String lk = cacheKey(tenantId, virtualModel, sessionKey);
        String localVal = local.getIfPresent(lk);
        if (localVal != null) {
            return Mono.just(Optional.of(Long.parseLong(localVal)));
        }
        return redis.opsForValue().get(redisKey(tenantId, virtualModel, sessionKey))
                .map(v -> {
                    local.put(lk, v);
                    return Optional.of(Long.parseLong(v));
                })
                .defaultIfEmpty(Optional.empty())
                .onErrorResume(e -> {
                    // Redis 抖动时亲和失效，退化为加权轮询——不影响正确性
                    log.debug("sticky lookup 失败，退化为轮询: {}", e.toString());
                    return Mono.just(Optional.empty());
                });
    }

    /**
     * 绑定会话到渠道。返回的 Mono 不阻塞请求路径（调用方可 subscribe 后不管）。
     */
    public Mono<Void> bind(long tenantId, String virtualModel, String sessionKey, long channelId) {
        if (sessionKey == null) {
            return Mono.empty();
        }
        String lk = cacheKey(tenantId, virtualModel, sessionKey);
        local.put(lk, Long.toString(channelId));

        return redis.opsForValue()
                .set(redisKey(tenantId, virtualModel, sessionKey), Long.toString(channelId), redisTtl)
                .onErrorResume(e -> {
                    log.debug("sticky 写入失败（仅影响跨实例亲和）: {}", e.toString());
                    return Mono.empty();
                })
                .then();
    }

    /**
     * 判断候选里是否有该渠道。用于校验映射是否仍然有效。
     */
    public static Optional<Candidate> findCandidate(java.util.List<Candidate> candidates, long channelId) {
        return candidates.stream().filter(c -> c.channel().id() == channelId).findFirst();
    }

    /** 仅用于测试与运维：清空本地缓存。 */
    public void invalidateLocal() {
        local.invalidateAll();
    }
}
