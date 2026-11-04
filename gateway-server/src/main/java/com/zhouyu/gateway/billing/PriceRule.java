package com.zhouyu.gateway.billing;

import java.math.BigDecimal;
import java.util.List;

/**
 * 分级定价规则。
 *
 * <p>价格单位：<b>每百万 token 的美元价</b>（与主流厂商报价口径一致）。
 * 计算时需除以 1_000_000，用 BigDecimal 避免浮点误差。
 *
 * <p>档位（tier）按顺序匹配，命中第一个满足条件的即停止。
 * 典型用法是"长上下文溢价"：输入 token 超过 200K 时单价翻倍。
 *
 * @param virtualModel 虚拟模型名
 * @param tiers        档位列表，按顺序匹配
 * @param channelMultiplier 渠道倍率（不同上游成本不同，如国产模型 0.15）
 * @param groupDiscount     分组折扣（如 VIP 0.9）
 */
public record PriceRule(
        String virtualModel,
        List<Tier> tiers,
        BigDecimal channelMultiplier,
        BigDecimal groupDiscount,
        MultimodalPricing multimodal
) {

    /**
     * 多模态计价（按量，不是按 token）。
     *
     * <p>为什么必须单独一套：图片/音频的成本与 token 无线性关系。
     * 例如 GPT-4o 生成一张 1024×1024 图片是固定 $0.04，与输出 token 无关；
     * 音频按秒计费（Whisper $0.006/分钟）。混进 token 单价会算错。
     *
     * @param imagePerUnit   每张图片的价格（USD），null 表示不按张计费
     * @param audioPerSecond 每秒音频的价格（USD），null 表示不按秒计费
     * @param videoPerSecond 每秒视频的价格（USD）
     */
    public record MultimodalPricing(
            BigDecimal imagePerUnit,
            BigDecimal audioPerSecond,
            BigDecimal videoPerSecond
    ) {
        public static MultimodalPricing none() {
            return new MultimodalPricing(null, null, null);
        }

        public boolean isEnabled() {
            return imagePerUnit != null || audioPerSecond != null || videoPerSecond != null;
        }
    }

    /** 兼容旧构造：无多模态计价。 */
    public PriceRule(String virtualModel, List<Tier> tiers,
                     BigDecimal channelMultiplier, BigDecimal groupDiscount) {
        this(virtualModel, tiers, channelMultiplier, groupDiscount, MultimodalPricing.none());
    }

    /**
     * 单个价格档位。
     *
     * @param inputTokensLte 输入 token 数 <= 此值时命中；null 表示无下限约束（兜底档）
     * @param inputPerMillion  输入价（USD / 1M tokens）
     * @param outputPerMillion 输出价
     * @param cacheWritePerMillion 缓存写入价
     * @param cacheReadPerMillion  缓存读取价（通常是输入价的 1/10）
     */
    public record Tier(
            Long inputTokensLte,
            BigDecimal inputPerMillion,
            BigDecimal outputPerMillion,
            BigDecimal cacheWritePerMillion,
            BigDecimal cacheReadPerMillion
    ) {
        public boolean matches(int inputTokens) {
            return inputTokensLte == null || inputTokens <= inputTokensLte;
        }
    }

    /**
     * 选择命中的档位。若都不命中则用最后一档（兜底）。
     */
    public Tier selectTier(int inputTokens) {
        for (Tier t : tiers) {
            if (t.matches(inputTokens)) {
                return t;
            }
        }
        return tiers.isEmpty() ? null : tiers.get(tiers.size() - 1);
    }
}
