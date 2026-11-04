package com.zhouyu.gateway.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TailUsageSnifferTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("从 Anthropic 流式片段中提取 input/output tokens")
    void parseAnthropicStreamFragments() {
        TailUsageSniffer sniffer = new TailUsageSniffer(mapper);
        sniffer.parseWhole(new byte[0]);
        sniffer.accept(buf("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":23,\"output_tokens\":1}}}\n\n"));
        sniffer.accept(buf("event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":57}}\n\n"));
        assertThat(sniffer.inputTokens()).isEqualTo(23);
        assertThat(sniffer.outputTokens()).isEqualTo(57);
        assertThat(sniffer.usageSource()).isEqualTo("UPSTREAM");
    }

    @Test
    @DisplayName("从 OpenAI 非流式响应中提取 prompt/completion tokens")
    void parseOpenAiNonStream() {
        TailUsageSniffer sniffer = new TailUsageSniffer(mapper);
        String json = "{\"id\":\"x\",\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":45}}";
        sniffer.parseWhole(json.getBytes(StandardCharsets.UTF_8));
        assertThat(sniffer.inputTokens()).isEqualTo(120);
        assertThat(sniffer.outputTokens()).isEqualTo(45);
    }

    @Test
    @DisplayName("从 Anthropic 格式（转换后）解析 input_tokens / output_tokens")
    void parseAnthropicConvertedResponse() {
        TailUsageSniffer sniffer = new TailUsageSniffer(mapper);
        String json = """
                {"id":"msg_gw_x","type":"message","role":"assistant",
                 "content":[{"type":"text","text":"hi"}],
                 "usage":{"input_tokens":27,"output_tokens":10}}
                """;
        sniffer.parseWhole(json.getBytes(StandardCharsets.UTF_8));
        assertThat(sniffer.inputTokens()).isEqualTo(27);
        assertThat(sniffer.outputTokens()).isEqualTo(10);
        assertThat(sniffer.usageSource()).isEqualTo("UPSTREAM");
    }

    @Test
    @DisplayName("从 Gemini 原始格式解析 usageMetadata")
    void parseGeminiResponse() {
        TailUsageSniffer sniffer = new TailUsageSniffer(mapper);
        String json = """
                {"candidates":[{"content":{"parts":[{"text":"hi"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":27,"candidatesTokenCount":10,"cachedContentTokenCount":5}}
                """;
        sniffer.parseWhole(json.getBytes(StandardCharsets.UTF_8));
        assertThat(sniffer.inputTokens()).isEqualTo(27);
        assertThat(sniffer.outputTokens()).isEqualTo(10);
        assertThat(sniffer.cachedTokens()).isEqualTo(5);
        assertThat(sniffer.usageSource()).isEqualTo("UPSTREAM");
    }

    @Test
    @DisplayName("没有 usage 时保持 ESTIMATED，不得伪造数据")
    void noUsageStaysEstimated() {
        TailUsageSniffer sniffer = new TailUsageSniffer(mapper);
        sniffer.accept(buf("data: {\"delta\":{\"text\":\"hi\"}}\n\n"));
        assertThat(sniffer.usageSource()).isEqualTo("ESTIMATED");
        assertThat(sniffer.inputTokens()).isZero();
    }

    @Test
    @DisplayName("读取 DataBuffer 不得移动读指针")
    void doesNotConsumeBuffer() {
        TailUsageSniffer sniffer = new TailUsageSniffer(mapper);
        org.springframework.core.io.buffer.DefaultDataBufferFactory factory =
                new org.springframework.core.io.buffer.DefaultDataBufferFactory();
        var buffer = factory.wrap("data: {\"usage\":{\"input_tokens\":5}}\n\n".getBytes(StandardCharsets.UTF_8));
        sniffer.accept(buffer);
        // 之后仍能完整读取
        String after = buffer.toString(StandardCharsets.UTF_8);
        assertThat(after).contains("input_tokens");
    }

    private static org.springframework.core.io.buffer.DataBuffer buf(String s) {
        return new org.springframework.core.io.buffer.DefaultDataBufferFactory()
                .wrap(s.getBytes(StandardCharsets.UTF_8));
    }
}
