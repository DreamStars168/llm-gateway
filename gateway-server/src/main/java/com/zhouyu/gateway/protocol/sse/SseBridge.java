package com.zhouyu.gateway.protocol.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouyu.gateway.protocol.anthropic.AnthropicSseEncoder;
import com.zhouyu.gateway.protocol.canonical.CanonicalChunk;
import com.zhouyu.gateway.protocol.openai.OpenAiChatStreamDecoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SSE 协议桥：把上游字节流按「解码 → 规范事件 → 编码」转换为客户端协议。
 *
 * <p>支持两种路径：
 * <ul>
 *   <li>OpenAI Chat 上游 → Anthropic 客户端（Claude Code 打国产模型）</li>
 *   <li>Anthropic 上游 → OpenAI Chat 客户端（反向）</li>
 * </ul>
 *
 * <p>转换是有损的：thinking、cache_control 等字段可能丢弃，已在 droppedFields 中标注。
 */
@Component
public class SseBridge {

    private final ObjectMapper mapper;

    public SseBridge(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 将 OpenAI 流式字节转换为 Anthropic SSE 文本流。
     *
     * @param upstream 上游原始字节（UTF-8 SSE）
     * @return 客户端可直接消费的 SSE 字符串流
     */
    public Flux<String> openAiToAnthropic(Flux<byte[]> upstream, com.zhouyu.gateway.relay.TailUsageSniffer meter) {
        return Flux.defer(() -> {
            SseFrameParser parser = new SseFrameParser();
            OpenAiChatStreamDecoder decoder = new OpenAiChatStreamDecoder(mapper);
            AnthropicSseEncoder encoder = new AnthropicSseEncoder();
            boolean[] finished = {false};

            return upstream
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .concatMap(text -> {
                        List<String> outputs = new ArrayList<>();
                        for (SseFrameParser.SseEvent event : parser.feed(text)) {
                            String data = event.data();
                            if (data == null || data.isBlank()) {
                                continue;
                            }
                            if ("[DONE]".equals(data.trim())) {
                                // 流正常结束：此时 usage 帧已处理完，可以安全收尾
                                if (!finished[0]) {
                                    finished[0] = true;
                                    String closing = encoder.encode(new CanonicalChunk.Finish("end_turn"));
                                    if (!closing.isEmpty()) {
                                        outputs.add(closing);
                                    }
                                }
                                continue;
                            }
                            for (CanonicalChunk chunk : decoder.decode(data)) {
                                meter.acceptCanonical(chunk);
                                // Finish 事件延迟到 [DONE] 或流结束再发，
                                // 保证其后的 usage 帧能先进入 pending 值
                                if (chunk instanceof CanonicalChunk.Finish) {
                                    continue;
                                }
                                String encoded = encoder.encode(chunk);
                                if (!encoded.isEmpty()) {
                                    outputs.add(encoded);
                                }
                            }
                        }
                        return Flux.fromIterable(outputs);
                    })
                    .concatWith(Flux.defer(() -> {
                        // 若上游没发 [DONE] 就断流，补齐收尾事件
                        if (finished[0]) {
                            return Flux.empty();
                        }
                        finished[0] = true;
                        List<String> tail = new ArrayList<>();
                        for (SseFrameParser.SseEvent e : parser.flush()) {
                            for (CanonicalChunk c : decoder.decode(e.data())) {
                                String s = encoder.encode(c);
                                if (!s.isEmpty()) {
                                    tail.add(s);
                                }
                            }
                        }
                        String closing = encoder.encode(new CanonicalChunk.Finish("end_turn"));
                        if (!closing.isEmpty()) {
                            tail.add(closing);
                        }
                        return Flux.fromIterable(tail);
                    }));
        });
    }

    /**
     * 将 Gemini 流式字节转换为 Anthropic SSE 文本流。
     *
     * <p>Gemini 没有 [DONE] 结束标记，靠流结束判定；
     * 因此必须在 {@code concatWith} 里补发 Finish，否则客户端会一直等。
     */
    public Flux<String> geminiToAnthropic(Flux<byte[]> upstream, com.zhouyu.gateway.relay.TailUsageSniffer meter) {
        return Flux.defer(() -> {
            SseFrameParser parser = new SseFrameParser();
            com.zhouyu.gateway.protocol.gemini.GeminiStreamDecoder decoder =
                    new com.zhouyu.gateway.protocol.gemini.GeminiStreamDecoder(mapper);
            AnthropicSseEncoder encoder = new AnthropicSseEncoder();
            boolean[] finished = {false};

            return upstream
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .concatMap(text -> {
                        List<String> outputs = new ArrayList<>();
                        for (SseFrameParser.SseEvent event : parser.feed(text)) {
                            String data = event.data();
                            if (data == null || data.isBlank()) {
                                continue;
                            }
                            for (CanonicalChunk chunk : decoder.decode(data)) {
                                meter.acceptCanonical(chunk);
                                if (chunk instanceof CanonicalChunk.Finish) {
                                    finished[0] = true;
                                }
                                String encoded = encoder.encode(chunk);
                                if (!encoded.isEmpty()) {
                                    outputs.add(encoded);
                                }
                            }
                        }
                        return Flux.fromIterable(outputs);
                    })
                    .concatWith(Flux.defer(() -> {
                        // Gemini 无 [DONE]，必须由网关补齐收尾事件
                        if (finished[0]) {
                            return Flux.empty();
                        }
                        String closing = encoder.encode(new CanonicalChunk.Finish("end_turn"));
                        return closing.isEmpty() ? Flux.empty() : Flux.just(closing);
                    }));
        });
    }

    /**
     * 将 Anthropic 流式字节转换为 OpenAI Chat SSE 文本流。
     */
    public Flux<String> anthropicToOpenAi(Flux<byte[]> upstream) {
        return Flux.defer(() -> {
            SseFrameParser parser = new SseFrameParser();
            AnthropicStreamDecoder decoder = new AnthropicStreamDecoder(mapper);
            OpenAiChatSseEncoder encoder = new OpenAiChatSseEncoder(mapper);

            return upstream
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .concatMap(text -> {
                        List<String> outputs = new ArrayList<>();
                        for (SseFrameParser.SseEvent event : parser.feed(text)) {
                            for (CanonicalChunk chunk : decoder.decode(event.event(), event.data())) {
                                outputs.add(encoder.encode(chunk));
                                if (chunk instanceof CanonicalChunk.Finish) {
                                    outputs.add("data: [DONE]\n\n");
                                }
                            }
                        }
                        return Flux.fromIterable(outputs);
                    })
                    .concatWith(Flux.defer(() -> {
                        String closing = encoder.encode(new CanonicalChunk.Finish("end_turn"));
                        List<String> tail = new ArrayList<>();
                        if (!closing.isEmpty()) {
                            tail.add(closing);
                            tail.add("data: [DONE]\n\n");
                        }
                        return Flux.fromIterable(tail);
                    }));
        });
    }
}

