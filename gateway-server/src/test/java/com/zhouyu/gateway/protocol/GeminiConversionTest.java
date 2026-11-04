package com.zhouyu.gateway.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouyu.gateway.protocol.anthropic.AnthropicParser;
import com.zhouyu.gateway.protocol.canonical.CanonicalChunk;
import com.zhouyu.gateway.protocol.canonical.CanonicalRequest;
import com.zhouyu.gateway.protocol.gemini.GeminiRequestBuilder;
import com.zhouyu.gateway.protocol.gemini.GeminiStreamDecoder;
import com.zhouyu.gateway.protocol.gemini.GeminiToAnthropicNonStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gemini 协议转换测试。
 */
class GeminiConversionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicParser parser = new AnthropicParser(mapper);
    private final GeminiRequestBuilder builder = new GeminiRequestBuilder(mapper);

    // ── 请求构造 ────────────────────────────────────────

    @Test
    @DisplayName("system 提升为顶层 systemInstruction，角色转 user/model")
    void systemAndRoles() throws Exception {
        String body = """
                {"model":"gemini-2.5-pro","max_tokens":100,"system":"Be terse.",
                 "messages":[
                   {"role":"user","content":"hi"},
                   {"role":"assistant","content":"hello"},
                   {"role":"user","content":"again"}]}
                """;
        CanonicalRequest ir = parser.parse(body.getBytes(StandardCharsets.UTF_8));
        JsonNode out = mapper.readTree(builder.build(ir, "gemini-2.5-pro"));

        assertThat(out.get("systemInstruction").get("parts").get(0).get("text").asText()).isEqualTo("Be terse.");
        JsonNode contents = out.get("contents");
        assertThat(contents.size()).isEqualTo(3);
        assertThat(contents.get(0).get("role").asText()).isEqualTo("user");
        assertThat(contents.get(1).get("role").asText()).isEqualTo("model");
        assertThat(contents.get(2).get("role").asText()).isEqualTo("user");
    }

    @Test
    @DisplayName("参数落到 generationConfig，不在顶层")
    void paramsGoIntoGenerationConfig() throws Exception {
        String body = """
                {"model":"gemini-2.5-pro","max_tokens":512,"temperature":0.3,"top_p":0.9,"top_k":40,
                 "stop_sequences":["END"],
                 "messages":[{"role":"user","content":"hi"}]}
                """;
        CanonicalRequest ir = parser.parse(body.getBytes(StandardCharsets.UTF_8));
        JsonNode out = mapper.readTree(builder.build(ir, "gemini-2.5-pro"));
        JsonNode gc = out.get("generationConfig");

        assertThat(gc.get("maxOutputTokens").asInt()).isEqualTo(512);
        assertThat(gc.get("temperature").asDouble()).isEqualTo(0.3);
        assertThat(gc.get("topP").asDouble()).isEqualTo(0.9);
        assertThat(gc.get("topK").asInt()).isEqualTo(40);
        assertThat(gc.get("stopSequences").get(0).asText()).isEqualTo("END");
        // 顶层不应出现 max_tokens
        assertThat(out.has("max_tokens")).isFalse();
    }

    @Test
    @DisplayName("工具 schema 的 type 必须转大写（Gemini 要求 OBJECT/STRING）")
    void schemaTypesUppercased() throws Exception {
        String body = """
                {"model":"g","max_tokens":100,
                 "tools":[{"name":"get_weather","description":"w",
                   "input_schema":{"type":"object","properties":{"city":{"type":"string"}}}}],
                 "messages":[{"role":"user","content":"weather?"}]}
                """;
        CanonicalRequest ir = parser.parse(body.getBytes(StandardCharsets.UTF_8));
        JsonNode out = mapper.readTree(builder.build(ir, "g"));
        JsonNode decl = out.get("tools").get(0).get("functionDeclarations").get(0);
        JsonNode params = decl.get("parameters");

        assertThat(params.get("type").asText()).isEqualTo("OBJECT");
        assertThat(params.get("properties").get("city").get("type").asText()).isEqualTo("STRING");
    }

    @Test
    @DisplayName("图片 base64 转 inlineData")
    void imageToInlineData() throws Exception {
        String body = """
                {"model":"g","max_tokens":10,
                 "messages":[{"role":"user","content":[
                   {"type":"text","text":"what"},
                   {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"ZZZ"}}]}]}
                """;
        CanonicalRequest ir = parser.parse(body.getBytes(StandardCharsets.UTF_8));
        JsonNode out = mapper.readTree(builder.build(ir, "g"));
        JsonNode parts = out.get("contents").get(0).get("parts");

        assertThat(parts.get(0).get("text").asText()).isEqualTo("what");
        assertThat(parts.get(1).get("inlineData").get("mimeType").asText()).isEqualTo("image/jpeg");
        assertThat(parts.get(1).get("inlineData").get("data").asText()).isEqualTo("ZZZ");
    }

    @Test
    @DisplayName("URL 形态：stream 需要 ?alt=sse")
    void urlShape() {
        assertThat(GeminiRequestBuilder.path("gemini-2.5-pro", false, false))
                .isEqualTo("/v1beta/models/gemini-2.5-pro:generateContent");
        assertThat(GeminiRequestBuilder.path("gemini-2.5-pro", true, false))
                .isEqualTo("/v1beta/models/gemini-2.5-pro:streamGenerateContent?alt=sse");
        assertThat(GeminiRequestBuilder.path("g", true, true))
                .contains("?alt=sse&key=");
    }

    // ── 流式解码 ────────────────────────────────────────

    @Test
    @DisplayName("流式解码：开始事件只发一次，usage 与 finish 正确")
    void streamDecode() {
        GeminiStreamDecoder d = new GeminiStreamDecoder(mapper);

        List<CanonicalChunk> a = d.decode(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello \"}],\"role\":\"model\"},\"finishReason\":null,\"index\":0}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":27,\"candidatesTokenCount\":1},\"modelVersion\":\"gemini-2.5-pro\"}");

        assertThat(a.stream().filter(c -> c instanceof CanonicalChunk.Start).count()).isEqualTo(1);
        assertThat(a).anyMatch(c -> c instanceof CanonicalChunk.TextDelta td && td.text().equals("hello "));
        assertThat(a).anyMatch(c -> c instanceof CanonicalChunk.UsageChunk u && u.inputTokens() == 27);

        List<CanonicalChunk> b = d.decode(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"world\"}],\"role\":\"model\"},\"finishReason\":\"STOP\",\"index\":0}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":27,\"candidatesTokenCount\":6}}");

        // Start 不应重复
        assertThat(b.stream().filter(c -> c instanceof CanonicalChunk.Start).count()).isZero();
        assertThat(b).anyMatch(c -> c instanceof CanonicalChunk.Finish f && f.stopReason().equals("end_turn"));
    }

    @Test
    @DisplayName("函数调用被解码为 ToolCallStart + Delta + End")
    void streamFunctionCall() {
        GeminiStreamDecoder d = new GeminiStreamDecoder(mapper);
        List<CanonicalChunk> chunks = d.decode(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"get_weather\",\"args\":{\"city\":\"SH\"}}}],\"role\":\"model\"},\"finishReason\":\"STOP\"}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5}}");

        assertThat(chunks).anyMatch(c -> c instanceof CanonicalChunk.ToolCallStart t
                && t.name().equals("get_weather"));
        assertThat(chunks).anyMatch(c -> c instanceof CanonicalChunk.ToolCallDelta);
        assertThat(chunks).anyMatch(c -> c instanceof CanonicalChunk.ToolCallEnd);
    }

    @Test
    @DisplayName("思考片段被解码为 ThinkingDelta")
    void streamThought() {
        GeminiStreamDecoder d = new GeminiStreamDecoder(mapper);
        List<CanonicalChunk> chunks = d.decode(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"let me think\",\"thought\":true}],\"role\":\"model\"},\"finishReason\":null}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2}}");
        assertThat(chunks).anyMatch(c -> c instanceof CanonicalChunk.ThinkingDelta td
                && td.text().equals("let me think"));
    }

    @Test
    @DisplayName("finishReason 映射：STOP→end_turn, MAX_TOKENS→max_tokens, SAFETY→content_filter")
    void finishReasonMapping() {
        assertThat(GeminiStreamDecoder.mapFinishReason("STOP")).isEqualTo("end_turn");
        assertThat(GeminiStreamDecoder.mapFinishReason("MAX_TOKENS")).isEqualTo("max_tokens");
        assertThat(GeminiStreamDecoder.mapFinishReason("SAFETY")).isEqualTo("content_filter");
    }

    @Test
    @DisplayName("错误响应被转成 ErrorChunk")
    void errorDecode() {
        GeminiStreamDecoder d = new GeminiStreamDecoder(mapper);
        List<CanonicalChunk> chunks = d.decode(
                "{\"error\":{\"code\":429,\"message\":\"quota exceeded\",\"status\":\"RESOURCE_EXHAUSTED\"}}");
        assertThat(chunks).anyMatch(c -> c instanceof CanonicalChunk.ErrorChunk e
                && e.message().contains("quota"));
    }

    // ── 非流式转换 ──────────────────────────────────────

    @Test
    @DisplayName("非流式响应转 Anthropic 格式，usage 正确")
    void nonStreamConvert() throws Exception {
        String gemini = """
                {"candidates":[{"content":{"parts":[{"text":"Hello there"}],"role":"model"},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":27,"candidatesTokenCount":12}}
                """;
        byte[] out = GeminiToAnthropicNonStream.convert(mapper, gemini.getBytes(StandardCharsets.UTF_8), "gemini-2.5-pro");
        JsonNode node = mapper.readTree(out);

        assertThat(node.get("type").asText()).isEqualTo("message");
        assertThat(node.get("role").asText()).isEqualTo("assistant");
        assertThat(node.get("content").get(0).get("text").asText()).isEqualTo("Hello there");
        assertThat(node.get("usage").get("input_tokens").asInt()).isEqualTo(27);
        assertThat(node.get("usage").get("output_tokens").asInt()).isEqualTo(12);
        assertThat(node.get("stop_reason").asText()).isEqualTo("end_turn");
    }
}
