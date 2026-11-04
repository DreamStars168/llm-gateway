package com.zhouyu.gateway.protocol.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouyu.gateway.protocol.canonical.CanonicalChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Chat 流式响应 → CanonicalChunk 列表。
 *
 * <p><b>有状态</b>：内部记录 lastId/lastModel 用于 Start 去重。
 * 必须<b>每请求一个实例</b>，不可作为单例 Bean 共享（会串数据）。
 * 因此本类不加 {@code @Component}，由调用方 new。
 */
public class OpenAiChatStreamDecoder {

    private final ObjectMapper mapper;
    private String lastId;
    private String lastModel;

    public OpenAiChatStreamDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 解码一个 SSE data 负载（已去掉 "data: " 前缀与换行）。
     *
     * @return 规范事件；遇到 [DONE] 返回空列表并由调用方收尾
     */
    public List<CanonicalChunk> decode(String data) {
        List<CanonicalChunk> out = new ArrayList<>();
        if (data == null || data.isBlank()) {
            return out;
        }
        String trimmed = data.trim();
        if ("[DONE]".equals(trimmed)) {
            return out;
        }

        JsonNode root;
        try {
            root = mapper.readTree(trimmed);
        } catch (Exception e) {
            return out;
        }

        // 错误帧
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            out.add(new CanonicalChunk.ErrorChunk(
                    error.path("type").asText("upstream_error"),
                    error.path("message").asText("上游返回错误")));
            return out;
        }

        String id = root.path("id").asText(null);
        String model = root.path("model").asText(null);
        // OpenAI 每个 chunk 都带 id/model，Start 只能发一次：
        // 用「与上一次不同」判断，首帧时 lastId 为 null 必然触发。
        if (id != null && !id.equals(lastId)) {
            lastId = id;
            out.add(new CanonicalChunk.Start(id, model == null ? lastModel : model));
        }
        if (model != null) {
            lastModel = model;
        }

        // usage 帧（stream_options.include_usage 时出现在最后）
        JsonNode usage = root.get("usage");
        if (usage != null && !usage.isNull()) {
            out.add(new CanonicalChunk.UsageChunk(
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    usage.path("prompt_tokens_details").path("cached_tokens").asInt(0),
                    usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0)));
        }

        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return out;
        }

        JsonNode choice = choices.get(0);
        JsonNode delta = choice.get("delta");
        if (delta != null) {
            // 推理内容（DeepSeek R1 等）
            JsonNode reasoning = delta.get("reasoning_content");
            if (reasoning != null && !reasoning.isNull() && !reasoning.asText().isEmpty()) {
                out.add(new CanonicalChunk.ThinkingDelta(reasoning.asText(), null));
            }

            JsonNode content = delta.get("content");
            if (content != null && !content.isNull() && !content.asText().isEmpty()) {
                out.add(new CanonicalChunk.TextDelta(content.asText()));
            }

            JsonNode toolCalls = delta.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    int index = tc.path("index").asInt(0);
                    JsonNode fn = tc.get("function");
                    String tcId = tc.path("id").asText(null);
                    if (tcId != null && !tcId.isEmpty()) {
                        String name = fn != null ? fn.path("name").asText("") : "";
                        out.add(new CanonicalChunk.ToolCallStart(tcId, name, index));
                    }
                    if (fn != null) {
                        String args = fn.path("arguments").asText("");
                        if (!args.isEmpty()) {
                            out.add(new CanonicalChunk.ToolCallDelta(index, args));
                        }
                    }
                }
            }
        }

        JsonNode finish = choice.get("finish_reason");
        if (finish != null && !finish.isNull()) {
            out.add(new CanonicalChunk.Finish(mapFinishReason(finish.asText())));
        }

        return out;
    }

    /**
     * OpenAI finish_reason → 规范结束原因。
     */
    public static String mapFinishReason(String openai) {
        return switch (openai) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "tool_calls", "function_call" -> "tool_use";
            case "content_filter" -> "content_filter";
            default -> "end_turn";
        };
    }
}
