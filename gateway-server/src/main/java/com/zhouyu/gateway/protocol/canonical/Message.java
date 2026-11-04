package com.zhouyu.gateway.protocol.canonical;

import java.util.List;

/**
 * 一条对话消息。system 独立于 messages，因为 Anthropic 与 Gemini 都有顶层 system 字段。
 */
public record Message(Role role, List<ContentPart> parts) {

    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL;

        /** OpenAI 风格字符串。 */
        public String wire() {
            return switch (this) {
                case SYSTEM -> "system";
                case USER -> "user";
                case ASSISTANT -> "assistant";
                case TOOL -> "tool";
            };
        }

        /** Gemini 风格：只有 user / model 两种角色。 */
        public String geminiWire() {
            return this == ASSISTANT ? "model" : "user";
        }
    }

    public static Message user(String text) {
        return new Message(Role.USER, List.of(ContentPart.TextPart.of(text)));
    }

    public static Message assistant(String text) {
        return new Message(Role.ASSISTANT, List.of(ContentPart.TextPart.of(text)));
    }

    public boolean hasToolUse() {
        return parts.stream().anyMatch(p -> p instanceof ContentPart.ToolUsePart);
    }
}
