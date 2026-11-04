package com.zhouyu.gateway.protocol.canonical;

/**
 * 规范流式事件。所有出口适配器把上游 SSE 解码成这些事件，
 * 所有入口适配器再编码成目标协议格式。
 *
 * <p>这样跨协议转换就变成「解码 → 事件流 → 编码」两段，
 * 每个方向只需理解自己那一侧的协议，不需要 N×N 组合。
 */
public sealed interface CanonicalChunk
        permits CanonicalChunk.Start, CanonicalChunk.TextDelta, CanonicalChunk.ThinkingDelta,
                CanonicalChunk.ToolCallStart, CanonicalChunk.ToolCallDelta, CanonicalChunk.ToolCallEnd,
                CanonicalChunk.UsageChunk, CanonicalChunk.Finish, CanonicalChunk.ErrorChunk {

    /** 响应开始，携带响应 ID 与模型名（客户端用于回显）。 */
    record Start(String responseId, String model) implements CanonicalChunk {
    }

    record TextDelta(String text) implements CanonicalChunk {
    }

    record ThinkingDelta(String text, String signature) implements CanonicalChunk {
    }

    /** 工具调用开始：id 与 name 确定，后续参数分片追加。 */
    record ToolCallStart(String id, String name, int index) implements CanonicalChunk {
    }

    /** 工具调用参数分片（原始 JSON 片段，不能提前解析）。 */
    record ToolCallDelta(int index, String partialJson) implements CanonicalChunk {
    }

    record ToolCallEnd(int index) implements CanonicalChunk {
    }

    record UsageChunk(int inputTokens, int outputTokens, int cachedTokens, int reasoningTokens) implements CanonicalChunk {
    }

    /** 结束原因：stop / length / tool_calls / content_filter / error。 */
    record Finish(String stopReason) implements CanonicalChunk {
    }

    record ErrorChunk(String type, String message) implements CanonicalChunk {
    }
}
