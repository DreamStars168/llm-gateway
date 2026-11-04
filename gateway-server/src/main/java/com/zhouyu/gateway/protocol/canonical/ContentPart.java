package com.zhouyu.gateway.protocol.canonical;

import java.util.List;

/**
 * 内容片段。跨协议转换的基本单元。
 *
 * <p>用 sealed interface 让转换器在 switch 时获得编译期穷尽检查——
 * 新增一种片段类型，所有转换器都会编译失败，不会漏改。
 */
public sealed interface ContentPart
        permits ContentPart.TextPart, ContentPart.ImagePart, ContentPart.ToolUsePart,
                ContentPart.ToolResultPart, ContentPart.ThinkingPart, ContentPart.DocumentPart {

    /** 纯文本。cacheControl 仅 Anthropic 原生支持，跨协议时丢弃。 */
    record TextPart(String text, String cacheControl) implements ContentPart {
        public static TextPart of(String text) {
            return new TextPart(text, null);
        }
    }

    /** 图片：base64 或 URL 二选一。 */
    record ImagePart(String mediaType, String data, boolean isUrl) implements ContentPart {
        public static ImagePart base64(String mediaType, String data) {
            return new ImagePart(mediaType, data, false);
        }

        public static ImagePart url(String url) {
            return new ImagePart(null, url, true);
        }
    }

    /** 工具调用（模型发起）。 */
    record ToolUsePart(String id, String name, com.fasterxml.jackson.databind.JsonNode input) implements ContentPart {
    }

    /** 工具结果（客户端回传）。 */
    record ToolResultPart(String toolUseId, List<ContentPart> content, boolean isError) implements ContentPart {
    }

    /** 思考内容。signature 为 Anthropic/Gemini 的多轮校验凭据，跨协议若目标不支持则丢弃。 */
    record ThinkingPart(String text, String signature) implements ContentPart {
    }

    /** 文档（仅 Anthropic 支持 PDF 输入）。 */
    record DocumentPart(String mediaType, String data, String title) implements ContentPart {
    }
}
