package com.zhouyu.gateway.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多模态计价测试。
 *
 * <p>验证图片按张、音频按秒与 token 计价能正确叠加，
 * 且倍率/折扣对多模态部分同样生效。
 */
class MultimodalBillingTest {

    private final CostCalculator calc = new CostCalculator();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MultimodalUsageExtractor extractor = new MultimodalUsageExtractor(mapper);

    private static PriceRule ruleWithMultimodal() {
        return new PriceRule("m", List.of(
                new PriceRule.Tier(null,
                        new BigDecimal("1.0"), new BigDecimal("2.0"),
                        BigDecimal.ZERO, BigDecimal.ZERO)
        ), BigDecimal.ONE, BigDecimal.ONE,
                new PriceRule.MultimodalPricing(
                        new BigDecimal("0.04"),      // 每张图 $0.04
                        new BigDecimal("0.001"),     // 每秒音频 $0.001
                        null));
    }

    // ── 计价 ──────────────────────────────────────────

    @Test
    @DisplayName("图片按张计费：2 张 × $0.04 = 80000 micro")
    void imagePerUnit() {
        var cost = calc.calculate(ruleWithMultimodal(), 0, 0, 0,
                new CostCalculator.MultimodalUsage(2, 0, 0));
        assertThat(cost.microUsd()).isEqualTo(80_000L);
    }

    @Test
    @DisplayName("音频按秒计费：30 秒 × $0.001 = 30000 micro")
    void audioPerSecond() {
        var cost = calc.calculate(ruleWithMultimodal(), 0, 0, 0,
                new CostCalculator.MultimodalUsage(0, 30.0, 0));
        assertThat(cost.microUsd()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("token 与多模态叠加：1000 输入 + 1 张图")
    void combinedTokenAndMultimodal() {
        // token: 1000/1M × 1.0 = 0.001 USD
        // image: 1 × 0.04 = 0.04 USD
        // 合计 0.041 USD = 41000 micro
        var cost = calc.calculate(ruleWithMultimodal(), 1000, 0, 0,
                new CostCalculator.MultimodalUsage(1, 0, 0));
        assertThat(cost.microUsd()).isEqualTo(41_000L);
    }

    @Test
    @DisplayName("倍率与折扣对多模态同样生效")
    void multiplierAppliesToMultimodal() {
        PriceRule rule = new PriceRule("m", List.of(
                new PriceRule.Tier(null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        ), new BigDecimal("0.5"), new BigDecimal("0.9"),
                new PriceRule.MultimodalPricing(new BigDecimal("0.04"), null, null));

        // 1 张图 0.04 × 0.5 × 0.9 = 0.018 USD = 18000 micro
        var cost = calc.calculate(rule, 0, 0, 0, new CostCalculator.MultimodalUsage(1, 0, 0));
        assertThat(cost.microUsd()).isEqualTo(18_000L);
    }

    @Test
    @DisplayName("未配置多模态时不额外收费")
    void noMultimodalNoExtraCost() {
        PriceRule plain = new PriceRule("m", List.of(
                new PriceRule.Tier(null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        ), BigDecimal.ONE, BigDecimal.ONE);

        var cost = calc.calculate(plain, 1000, 0, 0, new CostCalculator.MultimodalUsage(5, 60, 0));
        // 只有 token 部分：1000/1M × 1.0 = 0.001 USD = 1000 micro
        assertThat(cost.microUsd()).isEqualTo(1_000L);
    }

    // ── 用量提取 ──────────────────────────────────────

    @Test
    @DisplayName("提取 Anthropic 格式的图片数量")
    void extractAnthropicImages() {
        String body = """
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"text","text":"compare"},
                  {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAA"}},
                  {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"BBB"}}]}]}
                """;
        var usage = extractor.extract(body.getBytes(StandardCharsets.UTF_8));
        assertThat(usage.images()).isEqualTo(2);
    }

    @Test
    @DisplayName("提取 OpenAI 格式的图片数量")
    void extractOpenAiImages() {
        String body = """
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"image_url","image_url":{"url":"data:image/png;base64,AAA"}}]}]}
                """;
        var usage = extractor.extract(body.getBytes(StandardCharsets.UTF_8));
        assertThat(usage.images()).isEqualTo(1);
    }

    @Test
    @DisplayName("提取 Gemini 格式的图片数量（inlineData）")
    void extractGeminiImages() {
        String body = """
                {"contents":[{"role":"user","parts":[
                  {"inlineData":{"mimeType":"image/png","data":"AAA"}}]}]}
                """;
        var usage = extractor.extract(body.getBytes(StandardCharsets.UTF_8));
        assertThat(usage.images()).isEqualTo(1);
    }

    @Test
    @DisplayName("音频显式时长优先")
    void extractAudioExplicitDuration() {
        String body = """
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"audio","audio":{"data":"AAAA","duration_seconds":12.5}}]}]}
                """;
        var usage = extractor.extract(body.getBytes(StandardCharsets.UTF_8));
        assertThat(usage.audioSeconds()).isEqualTo(12.5);
    }

    @Test
    @DisplayName("纯文本请求不产生多模态用量")
    void plainTextHasNoMultimodal() {
        String body = """
                {"model":"m","messages":[{"role":"user","content":"just text"}]}
                """;
        var usage = extractor.extract(body.getBytes(StandardCharsets.UTF_8));
        assertThat(usage.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("畸形 JSON 不抛异常，返回空用量")
    void malformedJsonSafe() {
        var usage = extractor.extract("not json".getBytes(StandardCharsets.UTF_8));
        assertThat(usage.isEmpty()).isTrue();
    }
}
