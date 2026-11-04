package com.zhouyu.gateway.protocol.anthropic;

import com.zhouyu.gateway.protocol.canonical.CanonicalChunk;

/**
 * CanonicalChunk → Anthropic Messages SSE 事件。
 *
 * <p>Anthropic 事件顺序是严格约定的，顺序错了客户端会解析失败：
 * <pre>
 * message_start
 *   content_block_start(idx=0)
 *     content_block_delta × N
 *   content_block_stop(idx=0)
 *   content_block_start(idx=1)   ← 工具调用是新的 block
 *     content_block_delta × N
 *   content_block_stop(idx=1)
 * message_delta(usage, stop_reason)
 * message_stop
 * </pre>
 *
 * <p><b>有状态</b>：需要跟踪当前打开的 content block 索引与类型，
 * 因此每请求一个实例。
 */
public class AnthropicSseEncoder {

    private int nextIndex = 0;
    /** 当前打开的文本块索引；-1 表示未打开。 */
    private int openTextIndex = -1;
    /** 当前打开的工具块索引；-1 表示未打开。 */
    private int openToolIndex = -1;
    private boolean started = false;
    private boolean finished = false;
    private String model = "unknown";
    private String responseId;
    private int pendingInputTokens = 0;
    private int pendingOutputTokens = 0;

    /**
     * 保证 message_start 一定先发出。
     * 上游若未提供 Start（如某些兼容端点省略 id），这里补一个，避免客户端解析失败。
     */
    private String ensureStarted() {
        if (started) {
            return "";
        }
        started = true;
        if (responseId == null) {
            responseId = "msg_gw_" + Long.toHexString(System.nanoTime());
        }
        return event("message_start", """
                {"type":"message_start","message":{"id":"%s","type":"message","role":"assistant","model":"%s","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":0,"output_tokens":0}}}
                """.formatted(responseId, model));
    }

    /**
     * 编码一个规范事件为 0..N 个 SSE 文本块。
     *
     * @param chunk 规范事件
     * @return 可直接写出的 SSE 字符串（可能为空串）
     */
    public String encode(CanonicalChunk chunk) {
        if (chunk instanceof CanonicalChunk.Start s) {
            if (started) {
                return "";
            }
            responseId = s.responseId();
            model = s.model() == null ? "unknown" : s.model();
            return ensureStarted();
        }

        if (chunk instanceof CanonicalChunk.TextDelta d) {
            StringBuilder sb = new StringBuilder(ensureStarted());
            if (openTextIndex < 0) {
                openTextIndex = nextIndex++;
                sb.append(event("content_block_start", """
                        {"type":"content_block_start","index":%d,"content_block":{"type":"text","text":""}}
                        """.formatted(openTextIndex)));
            }
            sb.append(event("content_block_delta", """
                    {"type":"content_block_delta","index":%d,"delta":{"type":"text_delta","text":%s}}
                    """.formatted(openTextIndex, jsonString(d.text()))));
            return sb.toString();
        }

        if (chunk instanceof CanonicalChunk.ThinkingDelta d) {
            StringBuilder sb = new StringBuilder(ensureStarted());
            if (openTextIndex < 0) {
                // 思考块也用 text 形态回传（客户端可折叠展示）
                openTextIndex = nextIndex++;
                sb.append(event("content_block_start", """
                        {"type":"content_block_start","index":%d,"content_block":{"type":"thinking","thinking":""}}
                        """.formatted(openTextIndex)));
            }
            sb.append(event("content_block_delta", """
                    {"type":"content_block_delta","index":%d,"delta":{"type":"thinking_delta","thinking":%s}}
                    """.formatted(openTextIndex, jsonString(d.text()))));
            return sb.toString();
        }

        if (chunk instanceof CanonicalChunk.ToolCallStart t) {
            StringBuilder sb = new StringBuilder(ensureStarted());
            // 先关闭文本块
            sb.append(closeTextBlock());
            openToolIndex = nextIndex++;
            sb.append(event("content_block_start", """
                    {"type":"content_block_start","index":%d,"content_block":{"type":"tool_use","id":"%s","name":"%s","input":{}}}
                    """.formatted(openToolIndex, t.id(), t.name())));
            return sb.toString();
        }

        if (chunk instanceof CanonicalChunk.ToolCallDelta d) {
            if (openToolIndex < 0) {
                return "";
            }
            return event("content_block_delta", """
                    {"type":"content_block_delta","index":%d,"delta":{"type":"input_json_delta","partial_json":%s}}
                    """.formatted(openToolIndex, jsonString(d.partialJson())));
        }

        if (chunk instanceof CanonicalChunk.ToolCallEnd t) {
            if (openToolIndex < 0) {
                return "";
            }
            String s = event("content_block_stop", """
                    {"type":"content_block_stop","index":%d}
                    """.formatted(openToolIndex));
            openToolIndex = -1;
            return s;
        }

        if (chunk instanceof CanonicalChunk.UsageChunk u) {
            // Anthropic 的 usage 在 message_delta 里带出。
            // 若 Finish 已经发出（上游 usage 帧晚于 finish_reason 到达），
            // 则无法再补，属于协议时序限制；调用方应保证 Usage 在 Finish 之前。
            this.pendingInputTokens = Math.max(this.pendingInputTokens, u.inputTokens());
            this.pendingOutputTokens = Math.max(this.pendingOutputTokens, u.outputTokens());
            return "";
        }

        if (chunk instanceof CanonicalChunk.Finish f) {
            if (finished) {
                return "";
            }
            finished = true;
            StringBuilder sb = new StringBuilder();
            sb.append(closeTextBlock());
            sb.append(event("message_delta", """
                    {"type":"message_delta","delta":{"stop_reason":"%s","stop_sequence":null},"usage":{"input_tokens":%d,"output_tokens":%d}}
                    """.formatted(mapStopReason(f.stopReason()), pendingInputTokens, pendingOutputTokens)));
            sb.append(event("message_stop", """
                    {"type":"message_stop"}
                    """));
            return sb.toString();
        }

        if (chunk instanceof CanonicalChunk.ErrorChunk e) {
            return event("error", """
                    {"type":"error","error":{"type":"%s","message":%s}}
                    """.formatted(e.type(), jsonString(e.message())));
        }

        return "";
    }

    private String closeTextBlock() {
        if (openTextIndex < 0) {
            return "";
        }
        String s = event("content_block_stop", """
                {"type":"content_block_stop","index":%d}
                """.formatted(openTextIndex));
        openTextIndex = -1;
        return s;
    }

    static String mapStopReason(String canonical) {
        if (canonical == null) {
            return "end_turn";
        }
        return switch (canonical) {
            case "max_tokens", "length" -> "max_tokens";
            case "tool_use", "tool_calls" -> "tool_use";
            case "stop_sequence" -> "stop_sequence";
            case "content_filter" -> "end_turn";
            default -> "end_turn";
        };
    }

    private static String event(String name, String data) {
        return "event: " + name + "\n" + "data: " + data.trim() + "\n\n";
    }

    /** 安全地把字符串编码成 JSON 字符串字面量。 */
    private static String jsonString(String raw) {
        if (raw == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}

