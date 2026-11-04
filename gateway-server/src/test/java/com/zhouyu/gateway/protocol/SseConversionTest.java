package com.zhouyu.gateway.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouyu.gateway.protocol.anthropic.AnthropicSseEncoder;
import com.zhouyu.gateway.protocol.canonical.CanonicalChunk;
import com.zhouyu.gateway.protocol.openai.OpenAiChatStreamDecoder;
import com.zhouyu.gateway.protocol.sse.SseFrameParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SseConversionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ---------- SSE 帧解析器 ----------

    @Test
    @DisplayName("跨分片的 SSE 帧能被正确重组")
    void parserHandlesSplitFrames() {
        SseFrameParser parser = new SseFrameParser();
        assertThat(parser.feed("event: message_start\nda")).isEmpty();
        var events = parser.feed("ta: {\"type\":\"message_start\"}\n\n");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).event()).isEqualTo("message_start");
        assertThat(events.get(0).data()).isEqualTo("{\"type\":\"message_start\"}");
    }

    @Test
    @DisplayName("多行 data 会被拼接成单个事件")
    void parserJoinsMultiLineData() {
        SseFrameParser parser = new SseFrameParser();
        var events = parser.feed("data: line1\ndata: line2\n\n");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).data()).isEqualTo("line1\nline2");
    }

    @Test
    @DisplayName("注释行（心跳）被忽略")
    void parserIgnoresComments() {
        SseFrameParser parser = new SseFrameParser();
        assertThat(parser.feed(": keep-alive\n\n")).isEmpty();
    }

    @Test
    @DisplayName("CRLF 换行也能正确解析")
    void parserHandlesCrlf() {
        SseFrameParser parser = new SseFrameParser();
        var events = parser.feed("data: {\"a\":1}\r\n\r\n");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).data()).isEqualTo("{\"a\":1}");
    }

    // ---------- OpenAI 解码 ----------

    @Test
    @DisplayName("OpenAI 首帧带 role，Start 只出现一次")
    void decoderStartOnlyOnce() {
        OpenAiChatStreamDecoder decoder = new OpenAiChatStreamDecoder(mapper);
        String f1 = "{\"id\":\"c1\",\"model\":\"gpt-4o\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"Hi\"},\"index\":0}]}";
        String f2 = "{\"id\":\"c1\",\"model\":\"gpt-4o\",\"choices\":[{\"delta\":{\"content\":\" there\"},\"index\":0}]}";

        List<CanonicalChunk> a = decoder.decode(f1);
        List<CanonicalChunk> b = decoder.decode(f2);

        assertThat(a).anyMatch(c -> c instanceof CanonicalChunk.Start);
        assertThat(b).noneMatch(c -> c instanceof CanonicalChunk.Start);
        assertThat(a.stream().filter(c -> c instanceof CanonicalChunk.Start).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("finish_reason 映射：stop→end_turn, length→max_tokens, tool_calls→tool_use")
    void finishReasonMapping() {
        assertThat(OpenAiChatStreamDecoder.mapFinishReason("stop")).isEqualTo("end_turn");
        assertThat(OpenAiChatStreamDecoder.mapFinishReason("length")).isEqualTo("max_tokens");
        assertThat(OpenAiChatStreamDecoder.mapFinishReason("tool_calls")).isEqualTo("tool_use");
    }

    @Test
    @DisplayName("工具调用分片被解码为 Start + Delta")
    void decoderToolCalls() {
        OpenAiChatStreamDecoder decoder = new OpenAiChatStreamDecoder(mapper);
        List<CanonicalChunk> chunks = decoder.decode(
                "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}]}}]}");
        assertThat(chunks).anyMatch(c -> c instanceof CanonicalChunk.ToolCallStart ts
                && ts.id().equals("call_1") && ts.name().equals("get_weather"));

        List<CanonicalChunk> args = decoder.decode(
                "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"city\\\":\"}}]}}]}");
        assertThat(args).anyMatch(c -> c instanceof CanonicalChunk.ToolCallDelta);
    }

    @Test
    @DisplayName("usage 帧被解析为 UsageChunk")
    void decoderUsage() {
        OpenAiChatStreamDecoder decoder = new OpenAiChatStreamDecoder(mapper);
        List<CanonicalChunk> chunks = decoder.decode(
                "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}");
        assertThat(chunks).anyMatch(c -> c instanceof CanonicalChunk.UsageChunk u
                && u.inputTokens() == 10 && u.outputTokens() == 20);
    }

    // ---------- Anthropic 编码 ----------

    @Test
    @DisplayName("Anthropic 事件顺序：message_start → content_block_start → delta → stop → message_stop")
    void anthropicEventOrder() {
        AnthropicSseEncoder encoder = new AnthropicSseEncoder();
        List<String> out = new ArrayList<>();
        out.add(encoder.encode(new CanonicalChunk.Start("msg_1", "claude-sonnet-4")));
        out.add(encoder.encode(new CanonicalChunk.TextDelta("Hello")));
        out.add(encoder.encode(new CanonicalChunk.TextDelta(" world")));
        out.add(encoder.encode(new CanonicalChunk.UsageChunk(10, 2, 0, 0)));
        out.add(encoder.encode(new CanonicalChunk.Finish("end_turn")));

        String joined = String.join("", out);
        int iStart = joined.indexOf("message_start");
        int iBlockStart = joined.indexOf("content_block_start");
        int iDelta = joined.indexOf("content_block_delta");
        int iBlockStop = joined.indexOf("content_block_stop");
        int iMsgDelta = joined.indexOf("message_delta");
        int iMsgStop = joined.indexOf("message_stop");

        assertThat(iStart).isGreaterThanOrEqualTo(0);
        assertThat(iStart).isLessThan(iBlockStart);
        assertThat(iBlockStart).isLessThan(iDelta);
        assertThat(iDelta).isLessThan(iBlockStop);
        assertThat(iBlockStop).isLessThan(iMsgDelta);
        assertThat(iMsgDelta).isLessThan(iMsgStop);
    }

    @Test
    @DisplayName("未收到 Start 时自动补 message_start，不得静默丢内容")
    void encoderAutoStarts() {
        AnthropicSseEncoder encoder = new AnthropicSseEncoder();
        String out = encoder.encode(new CanonicalChunk.TextDelta("orphan"));
        assertThat(out).contains("message_start");
        assertThat(out).contains("orphan");
    }

    @Test
    @DisplayName("工具调用会先关闭文本块，再开新的 tool_use 块")
    void encoderClosesTextBeforeTool() {
        AnthropicSseEncoder encoder = new AnthropicSseEncoder();
        encoder.encode(new CanonicalChunk.Start("msg_1", "m"));
        encoder.encode(new CanonicalChunk.TextDelta("let me check"));
        String toolStart = encoder.encode(new CanonicalChunk.ToolCallStart("tu_1", "get_weather", 0));

        assertThat(toolStart).contains("content_block_stop");
        assertThat(toolStart).contains("tool_use");
        assertThat(toolStart.indexOf("content_block_stop")).isLessThan(toolStart.indexOf("tool_use"));
    }

    @Test
    @DisplayName("特殊字符（引号/换行）被正确转义，不破坏 JSON")
    void encoderEscapesSpecialChars() {
        AnthropicSseEncoder encoder = new AnthropicSseEncoder();
        encoder.encode(new CanonicalChunk.Start("msg_1", "m"));
        String out = encoder.encode(new CanonicalChunk.TextDelta("he said \"hi\"\nnewline\\slash"));
        // 每一行 data: 后面必须是合法 JSON
        String dataLine = out.lines().filter(l -> l.startsWith("data: ")).findFirst().orElseThrow();
        String json = dataLine.substring(6);
        assertThat(json).doesNotContain("\n\"");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> mapper.readTree(json));
    }

    @Test
    @DisplayName("重复 Finish 只发一次 message_stop")
    void encoderIdempotentFinish() {
        AnthropicSseEncoder encoder = new AnthropicSseEncoder();
        encoder.encode(new CanonicalChunk.Start("msg_1", "m"));
        String first = encoder.encode(new CanonicalChunk.Finish("end_turn"));
        String second = encoder.encode(new CanonicalChunk.Finish("end_turn"));
        assertThat(first).contains("message_stop");
        assertThat(second).isEmpty();
    }
}
