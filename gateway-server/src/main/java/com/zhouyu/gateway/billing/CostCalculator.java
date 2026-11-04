package com.zhouyu.gateway.billing;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 成本计算器。
 *
 * <p><b>精度要求</b>：金额一律用 {@link BigDecimal}，禁止 double。
 * 内部记账单位是 micro-USD（long，1 USD = 1_000_000），
 * 计算完成后统一放大并四舍五入到整数 micro，避免小数累积误差。
 *
 * <h3>计算公式</h3>
 * <pre>
 * raw = (input × inputRate + output × outputRate
 *      + cacheWrite × cacheWriteRate + cacheRead × cacheReadRate) / 1_000_000
 * cost = raw × channelMultiplier × groupDiscount
 * micro = round(cost × 1_000_000)
 * </pre>
 */
@Component
public class CostCalculator {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final BigDecimal MICRO = new BigDecimal("1000000");

    /**
     * 计算结果。
     *
     * @param microUsd 成本（micro-USD，已含倍率与折扣）
     * @param tier    命中的档位（便于日志与对账）
     */
    public record Cost(long microUsd, PriceRule.Tier tier) {
    }

    /**
     * 多模态用量。用于按张/按秒计费的请求。
     *
     * @param images     图片张数
     * @param audioSeconds 音频秒数（可为小数）
     * @param videoSeconds 视频秒数
     */
    public record MultimodalUsage(int images, double audioSeconds, double videoSeconds) {
        public static MultimodalUsage none() {
            return new MultimodalUsage(0, 0, 0);
        }

        public boolean isEmpty() {
            return images == 0 && audioSeconds == 0 && videoSeconds == 0;
        }
    }

    /**
     * 按规则计算成本（纯 token）。
     */
    public Cost calculate(PriceRule rule,
                          int inputTokens,
                          int outputTokens,
                          int cachedTokens) {
        return calculate(rule, inputTokens, outputTokens, cachedTokens, MultimodalUsage.none());
    }

    /**
     * 按规则计算成本（含多模态）。
     *
     * <p>多模态部分独立累加：{@code images × imagePerUnit + audioSeconds × audioPerSecond}，
     * 与 token 部分一起乘倍率和折扣——因为同一渠道的加价/折扣对多模态同样适用。
     */
    public Cost calculate(PriceRule rule,
                          int inputTokens,
                          int outputTokens,
                          int cachedTokens,
                          MultimodalUsage mm) {
        if (rule == null) {
            return new Cost(0L, null);
        }
        PriceRule.Tier tier = rule.selectTier(inputTokens);
        if (tier == null) {
            return new Cost(0L, null);
        }

        BigDecimal input = nz(tier.inputPerMillion()).multiply(BigDecimal.valueOf(inputTokens));
        BigDecimal output = nz(tier.outputPerMillion()).multiply(BigDecimal.valueOf(outputTokens));
        BigDecimal cacheRead = nz(tier.cacheReadPerMillion()).multiply(BigDecimal.valueOf(cachedTokens));

        BigDecimal sum = input.add(output).add(cacheRead);
        BigDecimal usd = sum.divide(ONE_MILLION, 12, RoundingMode.HALF_UP);

        // 多模态部分（已是绝对值 USD，不再除以 1M）
        PriceRule.MultimodalPricing mp = rule.multimodal();
        if (mp != null && mp.isEnabled() && mm != null && !mm.isEmpty()) {
            if (mp.imagePerUnit() != null && mm.images() > 0) {
                usd = usd.add(mp.imagePerUnit().multiply(BigDecimal.valueOf(mm.images())));
            }
            if (mp.audioPerSecond() != null && mm.audioSeconds() > 0) {
                usd = usd.add(mp.audioPerSecond()
                        .multiply(BigDecimal.valueOf(mm.audioSeconds()))
                        .setScale(12, RoundingMode.HALF_UP));
            }
            if (mp.videoPerSecond() != null && mm.videoSeconds() > 0) {
                usd = usd.add(mp.videoPerSecond()
                        .multiply(BigDecimal.valueOf(mm.videoSeconds()))
                        .setScale(12, RoundingMode.HALF_UP));
            }
        }

        BigDecimal multiplier = nz(rule.channelMultiplier()).compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ONE : rule.channelMultiplier();
        BigDecimal discount = nz(rule.groupDiscount()).compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ONE : rule.groupDiscount();

        BigDecimal finalUsd = usd.multiply(multiplier).multiply(discount);
        long micro = finalUsd.multiply(MICRO).setScale(0, RoundingMode.HALF_UP).longValue();
        return new Cost(Math.max(0L, micro), tier);
    }

    /**
     * 估算预扣额：按 max_tokens 作为输出上限，输入按实际 token 数。
     *
     * <p>预扣要覆盖最坏情况，否则并发下会透支。乘以 1.2 安全系数。
     */
    public long estimateReserveMicro(PriceRule rule, int estimatedInputTokens, int maxTokens) {
        if (rule == null) {
            return 1_000_000L; // 未配置定价时预扣 1 USD 兜底
        }
        Cost worst = calculate(rule, estimatedInputTokens, Math.max(1, maxTokens), 0);
        BigDecimal withBuffer = BigDecimal.valueOf(worst.microUsd())
                .multiply(new BigDecimal("1.2"))
                .setScale(0, RoundingMode.CEILING);
        return Math.max(1L, withBuffer.longValue());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
