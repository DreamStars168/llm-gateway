package com.zhouyu.gateway.protocol.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouyu.gateway.protocol.canonical.CanonicalChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Gemini {@code streamGenerateContent} 流式响应 → CanonicalChunk。
 *
 * <h3>Gemini 流式的特点</h3>
 * <ul>
 *   <li>每个 SSE data 是一个完整的 {@code GenerateContentResponse}，
 *       <b>没有</b> [DONE] 结束标记，靠连接关闭判定结束</li>
 *   <li>{@code candidates[0].content.parts[]} 里可能同时有 text、thought、functionCall</li>
 *   <li>{@code usageMetadata} 每个 chunk 都带（累计值），取最后一个即可</li>
 *   <li>{@code finishReason} 只在最后一个 chunk 出现</li>
 * </ul>
 */
public class GeminiStreamDecoder {

    private final ObjectMapper mapper;
    private boolean started = false;

    public GeminiStreamDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<CanonicalChunk> decode(String data) {
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

        // 错误响应
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            out.add(new CanonicalChunk.ErrorChunk(
                    error.path("status").asText("upstream_error"),
                    error.path("message").asText("Gemini 返回错误")));
            return out;
        }

        if (!started) {
            started = true;
            String model = root.path("modelVersion").asText("gemini");
            String responseId = root.path("responseId").asText("gem_resp_" + Long.toHexString(System.nanoTime()));
            out.add(new CanonicalChunk.Start(responseId, model));
        }

        // usage（累计值，每次覆盖）
        JsonNode usage = root.get("usageMetadata");
        if (usage != null && !usage.isNull()) {
            out.add(new CanonicalChunk.UsageChunk(
                    usage.path("promptTokenCount").asInt(0),
                    usage.path("candidatesTokenCount").asInt(0),
                    usage.path("cachedContentTokenCount").asInt(0),
                    usage.path("thoughtsTokenCount").asInt(0)));
        }

        JsonNode candidates = root.get("candidates");
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            return out;
        }

        JsonNode cand = candidates.get(0);
        JsonNode parts = cand.path("content").path("parts");
        if (parts.isArray()) {
            int toolIndex = 0;
            for (JsonNode part : parts) {
                // 思考片段
                if (part.path("thought").asBoolean(false)) {
                    String text = part.path("text").asText("");
                    if (!text.isEmpty()) {
                        out.add(new CanonicalChunk.ThinkingDelta(text, part.path("thoughtSignature").asText(null)));
                    }
                    continue;
                }
                // 工具调用
                JsonNode fc = part.get("functionCall");
                if (fc != null && !fc.isNull()) {
                    String name = fc.path("name").asText("");
                    // Gemini 无 id，用 name + 序号合成一个稳定 id，供下游协议使用
                    String id = "call_" + name + "_" + toolIndex;
                    out.add(new CanonicalChunk.ToolCallStart(id, name, toolIndex));
                    JsonNode args = fc.get("args");
                    if (args != null && !args.isNull()) {
                        try {
                            out.add(new CanonicalChunk.ToolCallDelta(toolIndex, mapper.writeValueAsString(args)));
                        } catch (Exception ignored) {
                        }
                    }
                    out.add(new CanonicalChunk.ToolCallEnd(toolIndex));
                    toolIndex++;
                    continue;
                }
                // 普通文本
                String text = part.path("text").asText("");
                if (!text.isEmpty()) {
                    out.add(new CanonicalChunk.TextDelta(text));
                }
            }
        }

        String finish = cand.path("finishReason").asText(null);
        if (finish != null && !finish.isEmpty() && !"FINISH_REASON_UNSPECIFIED".equals(finish)) {
            out.add(new CanonicalChunk.Finish(mapFinishReason(finish)));
        }

        return out;
    }

    /**
     * Gemini finishReason → 规范结束原因。
     */
    public static String mapFinishReason(String gemini) {
        return switch (gemini) {
            case "STOP" -> "end_turn";
            case "MAX_TOKENS" -> "max_tokens";
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> "content_filter";
            default -> "end_turn";
        };
    }
}
