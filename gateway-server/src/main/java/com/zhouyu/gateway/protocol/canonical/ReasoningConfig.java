package com.zhouyu.gateway.protocol.canonical;

/**
 * 推理/思考配置。
 *
 * @param enabled 是否开启
 * @param budgetTokens Anthropic 风格的思考预算
 * @param effort OpenAI 风格的档位（low/medium/high）
 */
public record ReasoningConfig(boolean enabled, Integer budgetTokens, String effort) {

    public static ReasoningConfig disabled() {
        return new ReasoningConfig(false, null, null);
    }

    /**
     * 把 Anthropic 的 budget_tokens 粗略映射为 OpenAI 的 effort 档位。
     * 这不是精确等价——两家语义不同，只能近似。
     */
    public String toEffort() {
        if (effort != null) {
            return effort;
        }
        if (budgetTokens == null) {
            return "medium";
        }
        if (budgetTokens <= 2048) {
            return "low";
        }
        if (budgetTokens <= 8192) {
            return "medium";
        }
        return "high";
    }

    /**
     * 反向：effort → budget。用于 OpenAI 入口转 Anthropic 出口。
     */
    public Integer toBudgetTokens() {
        if (budgetTokens != null) {
            return budgetTokens;
        }
        if (effort == null) {
            return null;
        }
        return switch (effort) {
            case "low", "minimal" -> 2048;
            case "high" -> 16384;
            default -> 8192;
        };
    }
}
