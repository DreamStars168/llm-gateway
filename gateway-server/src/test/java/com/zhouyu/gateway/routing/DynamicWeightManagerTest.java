package com.zhouyu.gateway.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动降权测试。
 *
 * <p>验证：
 * <ul>
 *   <li>连续失败后权重显著下降，但不归零（保留探测流量）</li>
 *   <li>恢复后权重回升</li>
 *   <li>429（限流）只轻微惩罚，不当作严重故障</li>
 * </ul>
 */
class DynamicWeightManagerTest {

    private static DynamicWeightManager manager() {
        return new DynamicWeightManager(true, 0.05, 0.5, 0.1, 300);
    }

    @Test
    @DisplayName("初始健康度为 1，权重等于基础权重")
    void initialWeight() {
        DynamicWeightManager m = manager();
        assertThat(m.health(1L, "m")).isEqualTo(1.0);
        assertThat(m.effectiveWeight(1L, "m", 100)).isEqualTo(100);
    }

    @Test
    @DisplayName("连续严重失败后权重下降，但不低于 minRatio")
    void penalizeOnFailures() {
        DynamicWeightManager m = manager();
        for (int i = 0; i < 10; i++) {
            m.recordFailure(1L, "m", true);
        }
        double h = m.health(1L, "m");
        assertThat(h).isLessThan(0.2);
        assertThat(h).isGreaterThan(0.0);

        int w = m.effectiveWeight(1L, "m", 100);
        // minRatio=0.05 → 至少 5
        assertThat(w).isGreaterThanOrEqualTo(5);
        assertThat(w).isLessThan(20);
    }

    @Test
    @DisplayName("成功后权重回升")
    void recoverAfterSuccess() {
        DynamicWeightManager m = manager();
        for (int i = 0; i < 5; i++) {
            m.recordFailure(1L, "m", true);
        }
        double low = m.health(1L, "m");

        for (int i = 0; i < 20; i++) {
            m.recordSuccess(1L, "m");
        }
        double high = m.health(1L, "m");

        assertThat(high).isGreaterThan(low);
        assertThat(high).isGreaterThan(0.8);
    }

    @Test
    @DisplayName("429 限流只轻微惩罚，不当作渠道故障")
    void rateLimitIsMildPenalty() {
        DynamicWeightManager severe = manager();
        DynamicWeightManager mild = manager();

        severe.recordFailure(1L, "m", true);   // 5xx
        mild.recordFailure(1L, "m", false);    // 429

        // 严重失败后健康度显著低于轻微失败
        assertThat(severe.health(1L, "m")).isLessThan(mild.health(1L, "m"));
        // 轻微失败仍保持较高健康度
        assertThat(mild.health(1L, "m")).isGreaterThan(0.8);
    }

    @Test
    @DisplayName("按渠道×模型隔离，互不影响")
    void isolatedByChannelAndModel() {
        DynamicWeightManager m = manager();
        for (int i = 0; i < 10; i++) {
            m.recordFailure(1L, "model-a", true);
        }
        assertThat(m.health(1L, "model-a")).isLessThan(0.1);
        assertThat(m.health(1L, "model-b")).isEqualTo(1.0);
        assertThat(m.health(2L, "model-a")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("enabled=false 时权重恒等于基础权重")
    void disabledKeepsBaseWeight() {
        DynamicWeightManager m = new DynamicWeightManager(false, 0.05, 0.5, 0.1, 300);
        for (int i = 0; i < 10; i++) {
            m.recordFailure(1L, "m", true);
        }
        assertThat(m.effectiveWeight(1L, "m", 100)).isEqualTo(100);
    }

    @Test
    @DisplayName("降权影响 SWRR 的选择概率")
    void affectsSelectionProbabilities() {
        DynamicWeightManager m = manager();
        RouteResolver resolver = new RouteResolver(
                new SmoothWeightedRoundRobin(), new CircuitBreaker(), new ChannelGates(), m);

        var ch1 = new com.zhouyu.gateway.domain.Channel(1, "a", com.zhouyu.gateway.domain.Protocol.ANTHROPIC,
                "http://localhost", "k", 10, 100, 1000, java.time.Duration.ofSeconds(30), true, true);
        var ch2 = new com.zhouyu.gateway.domain.Channel(2, "b", com.zhouyu.gateway.domain.Protocol.ANTHROPIC,
                "http://localhost", "k", 10, 100, 1000, java.time.Duration.ofSeconds(30), true, true);

        var cands = java.util.List.of(
                new RouteTable.Candidate(new com.zhouyu.gateway.domain.ModelRoute(1, "m", ch1, "up", 100, 1.0), ch1),
                new RouteTable.Candidate(new com.zhouyu.gateway.domain.ModelRoute(2, "m", ch2, "up", 100, 1.0), ch2));

        // 让渠道 a 严重降权
        for (int i = 0; i < 10; i++) {
            m.recordFailure(1L, "up", true);
        }

        int aCount = 0;
        for (int i = 0; i < 200; i++) {
            var d = resolver.resolve("m", cands, com.zhouyu.gateway.domain.Protocol.ANTHROPIC);
            if (d.selected().channel().id() == 1L) {
                aCount++;
            }
            resolver.release(d);
        }
        // 降权后 a 应显著少于一半
        assertThat(aCount).isLessThan(80);
    }
}
