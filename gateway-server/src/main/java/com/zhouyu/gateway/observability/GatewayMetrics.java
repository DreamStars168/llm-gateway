package com.zhouyu.gateway.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网关指标门面。
 *
 * <p>集中管理指标命名，避免各处手写字符串导致面板查询不到。
 * <b>高基数警告</b>：绝不能用 requestId / userId 作 tag。
 */
@Component
public class GatewayMetrics {

    private final MeterRegistry registry;

    private final Counter requests;
    private final Counter errors;
    private final Counter failovers;
    private final Counter quotaRejected;
    private final Counter stickyHit;
    private final Counter stickyRebind;
    private final Timer addedLatency;
    private final Timer quotaReserve;
    private final DistributionSummary tokens;
    private final AtomicLong inflight = new AtomicLong();

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.requests = Counter.builder("gateway.requests")
                .description("总请求数").register(registry);
        this.errors = Counter.builder("gateway.errors")
                .description("错误请求数").register(registry);
        this.failovers = Counter.builder("gateway.failovers")
                .description("故障转移次数").register(registry);
        this.quotaRejected = Counter.builder("gateway.quota.rejected")
                .description("因配额被拒的请求数").register(registry);
        this.stickyHit = Counter.builder("gateway.sticky.hit")
                .description("会话亲和命中次数").register(registry);
        this.stickyRebind = Counter.builder("gateway.sticky.rebind")
                .description("会话亲和被覆盖次数（抖动指标）").register(registry);

        this.addedLatency = Timer.builder("gateway.added.latency")
                .description("网关附加延迟")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.quotaReserve = Timer.builder("gateway.quota.reserve")
                .description("配额预扣耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.tokens = DistributionSummary.builder("gateway.tokens")
                .description("单请求 token 数")
                .baseUnit("tokens")
                .publishPercentiles(0.5, 0.95)
                .register(registry);

        registry.gauge("gateway.inflight", inflight, AtomicLong::get);
    }

    public void request(String protocolIn, String model) {
        requests.increment();
    }

    public void error(String code) {
        errors.increment();
    }

    public void failover() {
        failovers.increment();
    }

    public void quotaRejected() {
        quotaRejected.increment();
    }

    public void stickyHit() {
        stickyHit.increment();
    }

    public void stickyRebind() {
        stickyRebind.increment();
    }

    public void recordAddedLatency(Duration d) {
        addedLatency.record(d);
    }

    public void recordQuotaReserve(Duration d) {
        quotaReserve.record(d);
    }

    public void recordTokens(int input, int output) {
        tokens.record(input + output);
    }

    public void inflightInc() {
        inflight.incrementAndGet();
    }

    public void inflightDec() {
        inflight.decrementAndGet();
    }

    public MeterRegistry registry() {
        return registry;
    }
}
