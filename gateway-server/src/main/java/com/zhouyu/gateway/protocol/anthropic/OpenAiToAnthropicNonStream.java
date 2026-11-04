package com.zhouyu.gateway.protocol.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * OpenAI Chat 非流式响应 → Anthropic Messages 响应。
 *
 * <p>用于「Anthropic 客户端 + OpenAI 渠道 + 非流式」的组合。
 */
public final class OpenAiToAnthropicNonStream {

    private OpenAiToAnthropicNonStream() {
    }

    public static byte[] convert(ObjectMapper mapper, byte[] openAiBody, String requestedModel) {
        try {
            JsonNode root = mapper.readTree(openAiBody);

            // 上游错误原样透出（保持错误语义）
            if (root.has("error")) {
                return openAiBody;
            }

            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");

            ObjectNode out = mapper.createObjectNode();
            out.put("id", root.path("id").asText("msg_gw_" + Long.toHexString(System.nanoTime())));
            out.put("type", "message");
            out.put("role", "assistant");
            out.put("model", requestedModel != null ? requestedModel : root.path("model").asText("unknown"));

            ArrayNode content = out.putArray("content");

            String text = message.path("content").asText("");
            if (!text.isEmpty()) {
                ObjectNode block = content.addObject();
                block.put("type", "text");
                block.put("text", text);
            }

            JsonNode toolCalls = message.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    ObjectNode block = content.addObject();
                    block.put("type", "tool_use");
                    block.put("id", tc.path("id").asText("toolu_" + Long.toHexString(System.nanoTime())));
                    block.put("name", tc.path("function").path("name").asText(""));
                    String args = tc.path("function").path("arguments").asText("{}");
                    try {
                        block.set("input", mapper.readTree(args));
                    } catch (Exception e) {
                        block.set("input", mapper.createObjectNode());
                    }
                }
            }

            String finish = choice.path("finish_reason").asText("stop");
            out.put("stop_reason", switch (finish) {
                case "length" -> "max_tokens";
                case "tool_calls", "function_call" -> "tool_use";
                case "content_filter" -> "end_turn";
                default -> "end_turn";
            });
            out.putNull("stop_sequence");

            JsonNode usage = root.get("usage");
            ObjectNode u = out.putObject("usage");
            u.put("input_tokens", usage == null ? 0 : usage.path("prompt_tokens").asInt(0));
            u.put("output_tokens", usage == null ? 0 : usage.path("completion_tokens").asInt(0));

            return mapper.writeValueAsBytes(out);
        } catch (Exception e) {
            return openAiBody;
        }
    }
}
