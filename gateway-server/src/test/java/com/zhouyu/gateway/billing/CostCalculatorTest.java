package com.zhouyu.gateway.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成本计算测试。
 *
 * <p>重点验证：
 * <ul>
 *   <li>公式精度（BigDecimal，不丢精度）</li>
 *   <li>分级档位切换（长上下文溢价）</li>
 *   <li>渠道倍率与分组折扣的乘法顺序</li>
 *   <li>缓存 token 按 cacheRead 计价（而非 input）</li>
 * </ul>
 */
class CostCalculatorTest {

    private final CostCalculator calc = new CostCalculator();

    private static PriceRule sonnet() {
        return new PriceRule("claude-sonnet-4", List.of(
                new PriceRule.Tier(200_000L,
                        new BigDecimal("3.0"), new BigDecimal("15.0"),
                        new BigDecimal("3.75"), new BigDecimal("0.30")),
                new PriceRule.Tier(null,
                        new BigDecimal("6.0"), new BigDecimal("22.5"),
                        new BigDecimal("7.50"), new BigDecimal("0.60"))
        ), BigDecimal.ONE, BigDecimal.ONE);
    }

    @Test
    @DisplayName("基础计费：1000 输入 + 500 输出 = 10500 micro (0.0105 USD)")
    void basicCost() {
        // 1000/1M × 3.0 = 0.003 USD
        // 500/1M × 15.0 = 0.0075 USD
        // 合计 0.0105 USD = 10500 micro
        var cost = calc.calculate(sonnet(), 1000, 500, 0);
        assertThat(cost.microUsd()).isEqualTo(10500L);
    }

    @Test
    @DisplayName("长上下文溢价：超过 200K 输入时切到第二档，单价翻倍")
    void tierSwitch() {
        // 200K 输入：仍在第一档 → 200000/1M × 3.0 = 0.6 USD = 600000 micro
        var tier1 = calc.calculate(sonnet(), 200_000, 0, 0);
        assertThat(tier1.microUsd()).isEqualTo(600_000L);

        // 200001 输入：进入第二档 → 200001/1M × 6.0 = 1.200006 USD ≈ 1200006 micro
        var tier2 = calc.calculate(sonnet(), 200_001, 0, 0);
        assertThat(tier2.microUsd()).isEqualTo(1_200_006L);
        assertThat(tier2.tier().inputTokensLte()).isNull();
    }

    @Test
    @DisplayName("缓存 token 按 cacheRead 计价，不是按 input")
    void cachedTokensUseCacheRate() {
        // 10000 输入 + 10000 缓存读
        // input: 10000/1M × 3.0 = 0.03
        // cache: 10000/1M × 0.30 = 0.003
        // 合计 0.033 USD = 33000 micro
        var cost = calc.calculate(sonnet(), 10_000, 0, 10_000);
        assertThat(cost.microUsd()).isEqualTo(33_000L);

        // 若误把缓存按 input 算，会是 60000 micro——这里明确断言不是
        assertThat(cost.microUsd()).isNotEqualTo(60_000L);
    }

    @Test
    @DisplayName("渠道倍率与分组折扣按乘法叠加")
    void multiplierAndDiscount() {
        PriceRule cheap = new PriceRule("m", List.of(
                new PriceRule.Tier(null, new BigDecimal("10.0"), new BigDecimal("0"), BigDecimal.ZERO, BigDecimal.ZERO)
        ), new BigDecimal("0.15"), new BigDecimal("0.9"));

        // 1M 输入 × 10.0 = 10 USD
        // × 0.15（渠道倍率）= 1.5 USD
        // × 0.9（分组折扣）= 1.35 USD = 1350000 micro
        var cost = calc.calculate(cheap, 1_000_000, 0, 0);
        assertThat(cost.microUsd()).isEqualTo(1_350_000L);
    }

    @Test
    @DisplayName("未配置定价时返回 0，不得抛异常")
    void noRuleReturnsZero() {
        var cost = calc.calculate(null, 1000, 500, 0);
        assertThat(cost.microUsd()).isZero();
    }

    @Test
    @DisplayName("极小请求不产生负数或丢精度（向下取整到 0 是允许的）")
    void tinyRequest() {
        // 1 token 输入 / 1 token 输出：3.0/1M + 15/1M = 0.000018 USD = 18 micro
        var cost = calc.calculate(sonnet(), 1, 1, 0);
        assertThat(cost.microUsd()).isEqualTo(18L);
    }

    @Test
    @DisplayName("预扣估算含 1.2 安全系数，覆盖最坏情况")
    void estimateHasBuffer() {
        // 1000 输入 + max_tokens=1000 输出
        // = 1000/1M×3 + 1000/1M×15 = 0.018 USD = 18000 micro
        // × 1.2 = 21600 micro
        long est = calc.estimateReserveMicro(sonnet(), 1000, 1000);
        assertThat(est).isEqualTo(21_600L);
    }

    @Test
    @DisplayName("档位匹配：按顺序命中第一个满足条件的档")
    void tierOrderMatters() {
        PriceRule rule = sonnet();
        assertThat(rule.selectTier(100).inputPerMillion()).isEqualByComparingTo("3.0");
        assertThat(rule.selectTier(200_000).inputPerMillion()).isEqualByComparingTo("3.0");
        assertThat(rule.selectTier(200_001).inputPerMillion()).isEqualByComparingTo("6.0");
    }
}
