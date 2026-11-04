package com.zhouyu.gateway.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouyu.gateway.protocol.anthropic.AnthropicParser;
import com.zhouyu.gateway.protocol.canonical.CanonicalRequest;
import com.zhouyu.gateway.protocol.canonical.ContentPart;
import com.zhouyu.gateway.protocol.canonical.Message;
import com.zhouyu.gateway.protocol.openai.OpenAiChatRequestBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anthropic → IR → OpenAI Chat 的请求转换测试。
 */
class AnthropicToOpenAiRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicParser parser = new AnthropicParser(mapper);
    private final OpenAiChatRequestBuilder builder = new OpenAiChatRequestBuilder(mapper);

    @Test
    @DisplayName("system 提升为独立字段，再落到 OpenAI 首条 system 消息")
    void systemIsHoisted() throws Exception {
        String body = """
                {"model":"claude-sonnet-4","max_tokens":100,"system":"You are terse.",
                 "messages":[{"role":"user","content":"hi"}]}
                """;
        CanonicalRequest req = parser.parse(body.getBytes(StandardCharsets.UTF_8));
        assertThat(req.system()).isEqualTo("You are terse.");

        JsonNode out = mapper.readTree(builder.build(req, "gpt-4o"));
        assertThat(out.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(out.get("messages").get(0).get("content").asText()).isEqualTo("You are terse.");
        assertThat(out.get("model").asText()).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("tool_use 转 tool_calls，tool_result 转独立 tool 消息")
    void toolRoundTrip() throws Exception {
        String body = """
                {"model":"claude-sonnet-4","max_tokens":100,
                 "tools":[{"name":"get_weather","description":"weather","input_schema":{"type":"object","properties":{"city":{"type":"string"}}}}],
                 "messages":[
                   {"role":"user","content":"weather in SH?"},
                   {"role":"assistant","content":[
                     {"type":"text","text":"Let me check."},
                     {"type":"tool_use","id":"tu_1","name":"get_weather","input":{"city":"Shanghai"}}]},
                   {"role":"user","content":[
                     {"type":"tool_result","tool_use_id":"tu_1","content":[{"type":"text","text":"25C sunny"}]}]}
                 ]}
                """;
        CanonicalRequest req = parser.parse(body.getBytes(StandardCharsets.UTF_8));
        JsonNode out = mapper.readTree(builder.build(req, "gpt-4o"));
        JsonNode messages = out.get("messages");

        // user / assistant(含 tool_calls) / tool
        assertThat(messages.size()).isEqualTo(3);
        assertThat(messages.get(1).get("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(1).get("tool_calls").get(0).get("id").asText()).isEqualTo("tu_1");
        assertThat(messages.get(1).get("tool_calls").get(0).get("function").get("name").asText()).isEqualTo("get_weather");

        assertThat(messages.get(2).get("role").asText()).isEqualTo("tool");
        assertThat(messages.get(2).get("tool_call_id").asText()).isEqualTo("tu_1");
        assertThat(messages.get(2).get("content").asText()).isEqualTo("25C sunny");

        // 工具定义被搬运
        assertThat(out.get("tools").get(0).get("function").get("name").asText()).isEqualTo("get_weather");
    }

    @Test
    @DisplayName("图片 base64 转 image_url data URI")
    void imageConversion() throws Exception {
        String body = """
                {"model":"claude-sonnet-4","max_tokens":10,
                 "messages":[{"role":"user","content":[
                   {"type":"text","text":"what is this"},
                   {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}]}]}
                """;
        CanonicalRequest req = parser.parse(body.getBytes(StandardCharsets.UTF_8));
        JsonNode out = mapper.readTree(builder.build(req, "gpt-4o"));
        JsonNode content = out.get("messages").get(0).get("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.get(1).get("image_url").get("url").asText())
                .isEqualTo("data:image/png;base64,AAAA");
    }

    @Test
    @DisplayName("thinking budget 映射为 reasoning_effort")
    void thinkingMapsToEffort() throws Exception {
        String body = """
                {"model":"claude-sonnet-4","max_tokens":4096,
                 "thinking":{"type":"enabled","budget_tokens":16384},
                 "messages":[{"role":"user","content":"think hard"}]}
                """;
        CanonicalRequest req = parser.parse(body.getBytes(StandardCharsets.UTF_8));
        JsonNode out = mapper.readTree(builder.build(req, "o3"));
        assertThat(out.get("reasoning_effort").asText()).isEqualTo("high");
    }

    @Test
    @DisplayName("流式请求必须注入 stream_options.include_usage，否则拿不到计费数据")
    void streamInjectsUsageOption() throws Exception {
        String body = """
                {"model":"claude-sonnet-4","max_tokens":10,"stream":true,
                 "messages":[{"role":"user","content":"hi"}]}
                """;
        CanonicalRequest req = parser.parse(body.getBytes(StandardCharsets.UTF_8));
        JsonNode out = mapper.readTree(builder.build(req, "gpt-4o"));
        assertThat(out.get("stream").asBoolean()).isTrue();
        assertThat(out.get("stream_options").get("include_usage").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("content_block 为字符串时也能正确解析")
    void stringContent() {
        String body = """
                {"model":"m","max_tokens":10,"messages":[{"role":"user","content":"plain text"}]}
                """;
        CanonicalRequest req = parser.parse(body.getBytes(StandardCharsets.UTF_8));
        assertThat(req.messages()).hasSize(1);
        assertThat(req.messages().get(0).role()).isEqualTo(Message.Role.USER);
        assertThat(req.messages().get(0).parts().get(0)).isInstanceOf(ContentPart.TextPart.class);
    }
}
