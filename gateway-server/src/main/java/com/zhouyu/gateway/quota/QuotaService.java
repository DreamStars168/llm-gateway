package com.zhouyu.gateway.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 配额服务：两阶段（预扣 → 结算 / 释放）。
 *
 * <p>所有判定在 Redis 内由单个 Lua 脚本完成：一次 RTT、天然原子、并发不超卖。
 *
 * <h3>为什么是「按请求预扣」而不是「租约预取」</h3>
 * 曾实现过租约预取（一次预扣 N 份，本地扣减），附加延迟 P99 从 35ms 降到 5ms，
 * 但引入了计费正确性缺陷：租约剩余额度与 reserved 的核销时机难以与结算对齐，
 * 实测出现 balance 与 reserved 对不上账。
 * <b>对一个管钱的系统，账目正确优先于 5ms 延迟</b>，故回退到按请求预扣。
 * 租约方案的正确实现需要「余额分成 available/frozen 两个子账户 + 定时对账」，
 * 列为后续优化项（见 docs/09 压测报告）。
 *
 * <h3>降级策略</h3>
 * Redis 超时或不可用时返回 {@link ReserveResult#DEGRADED}，由调用方按 FAIL_OPEN 放行，
 * 请求结束后照常结算（结算脚本会走「无预扣记录 → 直接扣减」分支补记）。
 * 配额服务抖动绝不能让用户的对话失败。
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    /** 预扣结果。 */
    public enum ReserveResult { OK, INSUFFICIENT, DUPLICATE, DEGRADED }

    private final ReactiveStringRedisTemplate redis;
    private final RedisScript<Long> reserveScript;
    private final RedisScript<Long> settleScript;
    private final RedisScript<Long> releaseScript;
    private final AtomicLong quotaDegradedCounter = new AtomicLong();

    public QuotaService(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
        this.reserveScript = load("lua/quota_reserve.lua");
        this.settleScript = load("lua/quota_settle.lua");
        this.releaseScript = load("lua/quota_release.lua");
    }

    private static RedisScript<Long> load(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 预扣额度。同一 requestId 重复调用幂等。
     */
    public Mono<ReserveResult> reserve(long tenantId, long apiKeyId, String requestId, long amountMicro) {
        List<String> keys = List.of(
                "quota:" + tenantId + ":balance",
                "quota:" + tenantId + ":reserved",
                "reservation:" + requestId);
        List<String> args = List.of(
                Long.toString(amountMicro),
                Long.toString(Duration.ofMinutes(10).toSeconds()),
                Long.toString(tenantId),
                Long.toString(apiKeyId),
                Long.toString(System.currentTimeMillis()));

        return redis.execute(reserveScript, keys, args)
                .single()
                .map(code -> {
                    long c = code;
                    if (c == 1L) {
                        return ReserveResult.OK;
                    }
                    if (c == 2L) {
                        return ReserveResult.DUPLICATE;
                    }
                    return ReserveResult.INSUFFICIENT;
                })
                .timeout(Duration.ofMillis(800))
                .onErrorResume(e -> {
                    quotaDegradedCounter.incrementAndGet();
                    log.warn("配额预扣降级(FAIL_OPEN) requestId={} reason={}", requestId, e.toString());
                    return Mono.just(ReserveResult.DEGRADED);
                });
    }

    /**
     * 结算：reserved -= 预扣额，balance -= 实际成本。
     * 失败不影响用户（结算发生在响应之后）。
     */
    public Mono<Long> settle(long tenantId, String requestId, long actualMicro, String usageSource) {
        List<String> keys = List.of(
                "quota:" + tenantId + ":balance",
                "quota:" + tenantId + ":reserved",
                "reservation:" + requestId);
        return redis.execute(settleScript, keys,
                        List.of(Long.toString(actualMicro), usageSource))
                .single()
                .timeout(Duration.ofMillis(800))
                .onErrorResume(e -> {
                    quotaDegradedCounter.incrementAndGet();
                    log.warn("配额结算降级 requestId={} reason={}", requestId, e.toString());
                    return Mono.empty();
                });
    }

    /**
     * 释放预扣（上游失败或客户端取消且无任何输出）。
     */
    public Mono<Long> release(long tenantId, String requestId) {
        List<String> keys = List.of(
                "quota:" + tenantId + ":reserved",
                "reservation:" + requestId);
        return redis.execute(releaseScript, keys, List.of())
                .single()
                .timeout(Duration.ofMillis(800))
                .onErrorResume(e -> Mono.empty());
    }

    public long degradedCount() {
        return quotaDegradedCounter.get();
    }

    public Mono<Long> balance(long tenantId) {
        return redis.opsForValue().get("quota:" + tenantId + ":balance")
                .map(Long::parseLong)
                .defaultIfEmpty(0L);
    }

    public Mono<Long> reserved(long tenantId) {
        return redis.opsForValue().get("quota:" + tenantId + ":reserved")
                .map(Long::parseLong)
                .defaultIfEmpty(0L);
    }

    public Mono<Boolean> initBalance(long tenantId, long amountMicro) {
        return redis.opsForValue()
                .setIfAbsent("quota:" + tenantId + ":balance", Long.toString(amountMicro));
    }
}
