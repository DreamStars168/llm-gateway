package com.zhouyu.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 模拟 Gemini generateContent / streamGenerateContent。
 *
 * <p>关键行为对齐真实 API：
 * <ul>
 *   <li>流式无 [DONE] 标记，靠连接关闭结束</li>
 *   <li>usageMetadata 每个 chunk 都带（累计值）</li>
 *   <li>finishReason 只在最后一个 chunk 出现</li>
 *   <li>schema 类型要求大写（OBJECT/STRING）</li>
 * </ul>
 */
@RestController
public class MockGeminiController {

    private final ObjectMapper mapper = new ObjectMapper();

    @PostMapping(value = "/v1beta/models/**", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public Mono<Void> handle(@RequestBody String body,
                             @RequestHeader(value = "x-mock-status", required = false) Integer mockStatus,
                             @RequestHeader(value = "x-mock-delay-ms", required = false) Integer delayMs,
                             org.springframework.http.server.reactive.ServerHttpRequest request,
                             ServerHttpResponse response) {

        if (mockStatus != null && mockStatus >= 400) {
            return json(response, mockStatus,
                    "{\"error\":{\"code\":" + mockStatus + ",\"message\":\"forced\",\"status\":\"MOCK\"}}");
        }

        JsonNode req;
        try {
            req = mapper.readTree(body);
        } catch (Exception e) {
            return json(response, 400, "{\"error\":{\"code\":400,\"message\":\"bad json\",\"status\":\"INVALID\"}}");
        }

        // 通过路径判断流式：streamGenerateContent vs generateContent
        String path = request.getURI().getPath();
        boolean stream = path.contains(":streamGenerateContent");

        // 校验 schema 必须是大写类型（模拟真实 API 的严格性）
        JsonNode schema = req.path("tools").path(0).path("functionDeclarations").path(0).path("parameters").path("type");
        if (!schema.isMissingNode() && !schema.asText().isEmpty()) {
            String t = schema.asText();
            if (!t.equals(t.toUpperCase())) {
                return json(response, 400,
                        "{\"error\":{\"code\":400,\"message\":\"Invalid schema type: must be uppercase\",\"status\":\"INVALID_ARGUMENT\"}}");
            }
        }

        int inputTokens = 27;
        int chunks = 5;
        long gap = delayMs == null ? 30L : delayMs;
        DataBufferFactory f = response.bufferFactory();

        // 非流式：一次性返回完整响应
        if (!stream) {
            String body2 = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello from Gemini mock.\"}],\"role\":\"model\"},"
                    + "\"finishReason\":\"STOP\",\"index\":0}],"
                    + "\"usageMetadata\":{\"promptTokenCount\":" + inputTokens
                    + ",\"candidatesTokenCount\":10,\"totalTokenCount\":" + (inputTokens + 10) + "},"
                    + "\"modelVersion\":\"gemini-2.5-pro\"}";
            return json(response, 200, body2);
        }

        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
        response.getHeaders().set("Cache-Control", "no-cache");

        Flux<String> frames = Flux.concat(
                Mono.just(frame(inputTokens, "gemini-2.5-pro", "hello ", null, 1)),
                Flux.range(2, chunks - 1)
                        .delayElements(Duration.ofMillis(gap))
                        .map(i -> frame(inputTokens, "gemini-2.5-pro", "gem" + i + " ", null, i)),
                Mono.just(frame(inputTokens, "gemini-2.5-pro", "", "STOP", chunks + 5))
        );

        return response.writeWith(frames.map(s -> f.wrap(s.getBytes(StandardCharsets.UTF_8))));
    }

    private String frame(int promptTokens, String model, String text, String finish, int candTokens) {
        String finishJson = finish == null ? "null" : "\"" + finish + "\"";
        String parts = text.isEmpty() ? "[]" : "[{\"text\":\"" + text + "\"}]";
        return "data: {\"candidates\":[{\"content\":{\"parts\":" + parts + ",\"role\":\"model\"},"
                + "\"finishReason\":" + finishJson + ",\"index\":0}],"
                + "\"usageMetadata\":{\"promptTokenCount\":" + promptTokens
                + ",\"candidatesTokenCount\":" + candTokens
                + ",\"totalTokenCount\":" + (promptTokens + candTokens) + "},"
                + "\"modelVersion\":\"" + model + "\"}\n\n";
    }

    private Mono<Void> json(ServerHttpResponse response, int status, String json) {
        response.setStatusCode(HttpStatus.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8))));
    }
}
