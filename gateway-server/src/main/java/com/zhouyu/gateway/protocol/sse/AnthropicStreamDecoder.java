package com.zhouyu.gateway.protocol.sse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouyu.gateway.protocol.canonical.CanonicalChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic Messages 流事件 → CanonicalChunk。
 *
 * <p><b>有状态</b>：维护当前 content block 的索引与类型，
 * 因为 {@code content_block_delta} 不带类型，必须靠前面的 {@code content_block_start} 判断。
 */
public class AnthropicStreamDecoder {

    private final ObjectMapper mapper;
    /** index → "text" | "thinking" | "tool_use" */
    private final java.util.Map<Integer, String> blockTypes = new java.util.HashMap<>();
    private final java.util.Map<Integer, String> toolIds = new java.util.HashMap<>();

    public AnthropicStreamDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<CanonicalChunk> decode(String eventName, String data) {
        List<CanonicalChunk> out = new ArrayList<>();
        if (data == null || data.isBlank()) {
            return out;
        }
        JsonNode root;
        try {
            root = mapper.readTree(data);
        } catch (Exception e) {
            return out;
        }

        String type = eventName != null ? eventName : root.path("type").asText("");

        switch (type) {
            case "message_start" -> {
                JsonNode msg = root.path("message");
                out.add(new CanonicalChunk.Start(msg.path("id").asText(null), msg.path("model").asText(null)));
                JsonNode usage = msg.get("usage");
                if (usage != null) {
                    out.add(new CanonicalChunk.UsageChunk(
                            usage.path("input_tokens").asInt(0),
                            usage.path("output_tokens").asInt(0),
                            usage.path("cache_read_input_tokens").asInt(0), 0));
                }
            }
            case "content_block_start" -> {
                int index = root.path("index").asInt(0);
                JsonNode block = root.path("content_block");
                String blockType = block.path("type").asText("text");
                blockTypes.put(index, blockType);
                if ("tool_use".equals(blockType)) {
                    String id = block.path("id").asText("");
                    String name = block.path("name").asText("");
                    toolIds.put(index, id);
                    out.add(new CanonicalChunk.ToolCallStart(id, name, index));
                } else if ("thinking".equals(blockType)) {
                    String thinking = block.path("thinking").asText("");
                    if (!thinking.isEmpty()) {
                        out.add(new CanonicalChunk.ThinkingDelta(thinking, null));
                    }
                }
            }
            case "content_block_delta" -> {
                int index = root.path("index").asInt(0);
                JsonNode delta = root.path("delta");
                String deltaType = delta.path("type").asText("");
                switch (deltaType) {
                    case "text_delta" -> out.add(new CanonicalChunk.TextDelta(delta.path("text").asText("")));
                    case "thinking_delta" -> out.add(new CanonicalChunk.ThinkingDelta(
                            delta.path("thinking").asText(""), delta.path("signature").asText(null)));
                    case "input_json_delta" -> out.add(new CanonicalChunk.ToolCallDelta(
                            index, delta.path("partial_json").asText("")));
                    case "signature_delta" -> {
                        // 签名单独下发，忽略
                    }
                    default -> {
                    }
                }
            }
            case "content_block_stop" -> {
                int index = root.path("index").asInt(0);
                String t = blockTypes.get(index);
                if ("tool_use".equals(t)) {
                    out.add(new CanonicalChunk.ToolCallEnd(index));
                }
                blockTypes.remove(index);
                toolIds.remove(index);
            }
            case "message_delta" -> {
                JsonNode delta = root.path("delta");
                String stop = delta.path("stop_reason").asText(null);
                JsonNode usage = root.get("usage");
                if (usage != null) {
                    out.add(new CanonicalChunk.UsageChunk(
                            usage.path("input_tokens").asInt(0),
                            usage.path("output_tokens").asInt(0), 0, 0));
                }
                if (stop != null && !stop.isEmpty()) {
                    out.add(new CanonicalChunk.Finish(stop));
                }
            }
            case "message_stop" -> {
                // 收尾由调用方的 Finish 触发，这里不重复发
            }
            case "error" -> {
                JsonNode err = root.path("error");
                out.add(new CanonicalChunk.ErrorChunk(
                        err.path("type").asText("upstream_error"),
                        err.path("message").asText("上游错误")));
            }
            case "ping" -> {
                // 忽略
            }
            default -> {
            }
        }
        return out;
    }
}
