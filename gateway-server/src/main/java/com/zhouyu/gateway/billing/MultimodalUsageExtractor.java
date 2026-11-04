package com.zhouyu.gateway.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 从请求体中提取多模态用量。
 *
 * <p>为什么要在网关侧统计：上游一般只返回 token 用量，
 * 不告诉你"这次请求包含几张图、几秒音频"。而按张/按秒计费必须知道这些。
 *
 * <h3>统计口径</h3>
 * <ul>
 *   <li><b>图片</b>：Anthropic {@code image} block、OpenAI {@code image_url}、Gemini {@code inlineData}(image/*)</li>
 *   <li><b>音频</b>：请求里带 {@code audio} 或 {@code input_audio} 字段时，
 *       取 {@code duration_seconds}；缺失则按 base64 长度估算（约 16KB/秒 for 16kHz PCM）</li>
 *   <li><b>视频</b>：同理，按 {@code video} 字段统计</li>
 * </ul>
 */
@Component
public class MultimodalUsageExtractor {

    /** 16kHz 16bit 单声道 PCM 每秒约 32KB；base64 膨胀 4/3 后约 42.7KB/秒。 */
    private static final double BYTES_PER_AUDIO_SECOND = 32_000 * 4.0 / 3.0;

    private final ObjectMapper mapper;

    public MultimodalUsageExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public CostCalculator.MultimodalUsage extract(byte[] body) {
        try {
            JsonNode root = mapper.readTree(body);
            int images = countImages(root);
            double audio = sumSeconds(root, "audio", "input_audio");
            double video = sumSeconds(root, "video", "input_video", "video_url");
            return new CostCalculator.MultimodalUsage(images, audio, video);
        } catch (Exception e) {
            return CostCalculator.MultimodalUsage.none();
        }
    }

    /** 递归统计图片数量（兼容三种协议的结构）。 */
    private int countImages(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0;
        }
        int count = 0;

        if (node.isArray()) {
            for (JsonNode child : node) {
                count += countImages(child);
            }
            return count;
        }

        if (!node.isObject()) {
            return 0;
        }

        // Anthropic: {"type":"image","source":{...}}
        if ("image".equals(node.path("type").asText())) {
            count++;
            return count;
        }
        // OpenAI: {"type":"image_url","image_url":{...}}
        if ("image_url".equals(node.path("type").asText()) || node.has("image_url")) {
            count++;
            return count;
        }
        // Gemini: {"inlineData":{"mimeType":"image/png","data":"..."}}
        JsonNode inline = node.get("inlineData");
        if (inline != null && inline.isObject()) {
            String mime = inline.path("mimeType").asText("");
            if (mime.startsWith("image/")) {
                count++;
                return count;
            }
        }
        // Gemini: fileData
        JsonNode fileData = node.get("fileData");
        if (fileData != null && fileData.isObject()) {
            String mime = fileData.path("mimeType").asText("");
            if (mime.startsWith("image/")) {
                count++;
                return count;
            }
        }

        var fields = node.fields();
        while (fields.hasNext()) {
            var e = fields.next();
            count += countImages(e.getValue());
        }
        return count;
    }

    /**
     * 累加音频/视频秒数。优先读显式 {@code duration_seconds}，
     * 缺失时按 base64 数据长度估算。
     */
    private double sumSeconds(JsonNode node, String... keys) {
        if (node == null || node.isNull()) {
            return 0;
        }
        double total = 0;

        if (node.isArray()) {
            for (JsonNode child : node) {
                total += sumSeconds(child, keys);
            }
            return total;
        }
        if (!node.isObject()) {
            return 0;
        }

        for (String key : keys) {
            JsonNode block = node.get(key);
            if (block == null || block.isNull() || !block.isObject()) {
                continue;
            }
            // 显式时长优先
            JsonNode dur = block.get("duration_seconds");
            if (dur != null && dur.isNumber()) {
                total += dur.asDouble();
                continue;
            }
            // 退化：按 data 长度估算
            String data = block.path("data").asText("");
            if (!data.isEmpty()) {
                total += data.length() / BYTES_PER_AUDIO_SECOND;
            }
        }

        var fields = node.fields();
        while (fields.hasNext()) {
            var e = fields.next();
            // 避免重复统计已处理的 key
            boolean skip = false;
            for (String k : keys) {
                if (k.equals(e.getKey())) {
                    skip = true;
                    break;
                }
            }
            if (!skip) {
                total += sumSeconds(e.getValue(), keys);
            }
        }
        return total;
    }
}
