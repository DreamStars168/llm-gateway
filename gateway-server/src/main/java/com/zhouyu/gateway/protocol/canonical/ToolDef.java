package com.zhouyu.gateway.protocol.canonical;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 工具定义。inputSchema 统一用 JSON Schema 表达，三家协议都能映射。
 */
public record ToolDef(String name, String description, JsonNode inputSchema) {

    public record ToolChoice(String mode, String toolName) {
        public static ToolChoice auto() {
            return new ToolChoice("auto", null);
        }

        public static ToolChoice required() {
            return new ToolChoice("required", null);
        }

        public static ToolChoice none() {
            return new ToolChoice("none", null);
        }

        public static ToolChoice of(String toolName) {
            return new ToolChoice("tool", toolName);
        }
    }

    public static List<ToolDef> emptyList() {
        return List.of();
    }
}
