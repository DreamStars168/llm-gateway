package com.zhouyu.gateway.domain;

/**
 * 上游协议类型。决定使用哪个出口适配器。
 */
public enum Protocol {
    OPENAI_CHAT,
    OPENAI_RESPONSES,
    ANTHROPIC,
    GEMINI;

    public static Protocol of(String value) {
        if (value == null) {
            return ANTHROPIC;
        }
        return switch (value.trim().toUpperCase()) {
            case "OPENAI_CHAT", "OPENAI" -> OPENAI_CHAT;
            case "OPENAI_RESPONSES", "RESPONSES" -> OPENAI_RESPONSES;
            case "GEMINI" -> GEMINI;
            default -> ANTHROPIC;
        };
    }
}
