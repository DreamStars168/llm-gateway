package com.zhouyu.gateway.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自动降权（动态权重调节）。
 *
 * <h3>与熔断的区别</h3>
 * <ul>
 *   <li><b>熔断</b>是开关：失败率超阈值就完全摘除，恢复前完全不接流量（0 或 1）</li>
 *   <li><b>降权</b>是节流：渠道还能用，但按健康度分到更少的流量，避免"半死不活"的渠道
 *       拖累整体成功率，同时保留少量探测流量以便自愈</li>
 * </ul>
 *
 * <h3>算法</h3>
 * 维护每个「渠道 × 模型」的健康分 {@code health ∈ [0,1]}：
 * <ol>
 *   <li>每次成功：{@code health += (1 - health) × recoverStep}（向 1 逼近）</li>
 *   <li>每次失败：{@code health -= (health - 0) × penalizeStep}（向 0 逼近）</li>
 *   <li>实际权重 = {@code baseWeight × max(minRatio, health)}</li>
 * </ol>
 *
 * <p>用指数滑动而非固定步长，好处是：连续失败会快速降到底（幂等收敛），
 * 而偶发失败只轻微影响；恢复同理，避免抖动。
 *
 * <h3>为什么不用定时任务重算</h3>
 * 调度时实时计算即可（O(1) 查表），无需后台扫描。
 * 定时任务只用于「长期无流量时缓慢回归中位」，避免历史坏账永久压低权重。
 */
@Component
public class DynamicWeightManager {

    private static final Logger log = LoggerFactory.getLogger(DynamicWeightManager.class);

    /** 健康分下界：再差也保留一点流量用于探测自愈。 */
    private final double minRatio;
    /** 单次失败的健康分衰减比例。 */
    private final double penalizeStep;
    /** 单次成功的健康分回升比例。 */
    private final double recoverStep;
    /** 空闲多久后开始向 1 回归（毫秒）。 */
    private final long idleResetMs;

    private final Map<String, Health> healthMap = new ConcurrentHashMap<>();

    public DynamicWeightManager(
            @Value("${gateway.dynamic-weight.enabled:true}") boolean enabled,
            @Value("${gateway.dynamic-weight.min-ratio:0.05}") double minRatio,
            @Value("${gateway.dynamic-weight.penalize-step:0.5}") double penalizeStep,
            @Value("${gateway.dynamic-weight.recover-step:0.1}") double recoverStep,
            @Value("${gateway.dynamic-weight.idle-reset-seconds:300}") long idleResetSeconds) {
        this.enabled = enabled;
        this.minRatio = clamp(minRatio, 0.01, 1.0);
        this.penalizeStep = clamp(penalizeStep, 0.01, 1.0);
        this.recoverStep = clamp(recoverStep, 0.01, 1.0);
        this.idleResetMs = idleResetSeconds * 1000L;
    }

    private final boolean enabled;

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String key(long channelId, String model) {
        return channelId + ":" + model;
    }

    /**
     * 计算该渠道当前的生效权重。
     *
     * @param baseWeight 配置里的基础权重
     * @return 生效权重（不会低于 baseWeight × minRatio）
     */
    public int effectiveWeight(long channelId, String model, int baseWeight) {
        if (!enabled) {
            return baseWeight;
        }
        Health h = healthMap.get(key(channelId, model));
        if (h == null) {
            return baseWeight;
        }
        double ratio = Math.max(minRatio, h.health);
        return Math.max(1, (int) Math.round(baseWeight * ratio));
    }

    /** 当前健康分，用于管理端展示与测试。 */
    public double health(long channelId, String model) {
        Health h = healthMap.get(key(channelId, model));
        return h == null ? 1.0 : h.health;
    }

    public void recordSuccess(long channelId, String model) {
        if (!enabled) return;
        healthMap.computeIfAbsent(key(channelId, model), k -> new Health()).onSuccess(recoverStep);
    }

    /**
     * 记录失败。
     *
     * @param severe 是否为严重失败（5xx / 连接失败为 true，429 为 false——
     *               限流不代表渠道坏了，只应轻微惩罚）
     */
    public void recordFailure(long channelId, String model, boolean severe) {
        if (!enabled) return;
        double step = severe ? penalizeStep : penalizeStep * 0.3;
        healthMap.computeIfAbsent(key(channelId, model), k -> new Health()).onFailure(step);
    }

    /**
     * 空闲回归：长时间无请求的渠道，健康分缓慢回升到 1。
     * 避免一次历史故障把权重永久压低。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void recoverIdle() {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        healthMap.forEach((k, h) -> {
            if (now - h.lastTouched.get() > idleResetMs && h.health < 1.0) {
                h.health = Math.min(1.0, h.health + 0.2);
                if (h.health >= 1.0) {
                    healthMap.remove(k);
                }
            }
        });
    }

    /** 仅测试用：清空状态。 */
    public void reset() {
        healthMap.clear();
    }

    private static final class Health {
        /** 初始 1.0（完全健康）。 */
        volatile double health = 1.0;
        final AtomicLong lastTouched = new AtomicLong(System.currentTimeMillis());

        synchronized void onSuccess(double step) {
            health = health + (1.0 - health) * step;
            if (health > 0.999) {
                health = 1.0;
            }
            lastTouched.set(System.currentTimeMillis());
        }

        synchronized void onFailure(double step) {
            health = health - health * step;
            if (health < 0.001) {
                health = 0.001;
            }
            lastTouched.set(System.currentTimeMillis());
        }
    }
}
