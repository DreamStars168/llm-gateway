package com.zhouyu.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * 模拟 Anthropic /v1/messages。
 *
 * <p>关键点：SSE 必须直接写 {@link DataBuffer}，若返回 {@code Flux<String>} 并声明
 * {@code text/event-stream}，Spring 会按 SSE 规范再次编码，产生重复的 {@code data:} 前缀。
 */
@RestController
public class MockMessagesController {

    private final ObjectMapper mapper = new ObjectMapper();

    @PostMapping("/v1/messages")
    public Mono<Void> messages(@RequestBody String body,
                               @RequestHeader(value = "x-mock-status", required = false) Integer mockStatus,
                               @RequestHeader(value = "x-mock-delay-ms", required = false) Integer delayMs,
                               ServerHttpResponse response) {

        if (mockStatus != null && mockStatus >= 400) {
            return json(response, mockStatus, "{\"type\":\"error\",\"error\":{\"type\":\"mock_error\",\"message\":\"forced " + mockStatus + "\"}}");
        }

        JsonNode req;
        try {
            req = mapper.readTree(body);
        } catch (Exception e) {
            return json(response, 400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad json\"}}");
        }

        boolean stream = req.path("stream").asBoolean(false);
        String model = req.path("model").asText("unknown");
        int maxTokens = req.path("max_tokens").asInt(64);

        if (!stream) {
            String json = """
                    {"id":"msg_%s","type":"message","role":"assistant","model":"%s",
                     "content":[{"type":"text","text":"Hello from mock upstream."}],
                     "stop_reason":"end_turn",
                     "usage":{"input_tokens":23,"output_tokens":9}}
                    """.formatted(UUID.randomUUID().toString().replace("-", "").substring(0, 20), model);
            return json(response, 200, json);
        }

        int chunks = Math.min(40, Math.max(3, maxTokens / 4));
        long perChunk = delayMs == null ? 40L : delayMs;
        String msgId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        DataBufferFactory factory = response.bufferFactory();

        response.setStatusCode(org.springframework.http.HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
        response.getHeaders().set("Cache-Control", "no-cache");

        Flux<String> payload = Flux.concat(
                Mono.just(sse("message_start", """
                        {"type":"message_start","message":{"id":"%s","type":"message","role":"assistant","model":"%s","content":[],"stop_reason":null,"usage":{"input_tokens":23,"output_tokens":1}}}
                        """.formatted(msgId, model))),
                Mono.just(sse("content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")),
                Flux.range(1, chunks)
                        .delayElements(Duration.ofMillis(perChunk))
                        .map(i -> sse("content_block_delta",
                                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"token" + i + " \"}}")),
                Mono.just(sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")),
                Mono.just(sse("message_delta",
                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":" + chunks + "}}")),
                Mono.just(sse("message_stop", "{\"type\":\"message_stop\"}"))
        );

        return response.writeWith(payload.map(s -> factory.wrap(s.getBytes(StandardCharsets.UTF_8))));
    }

    private Mono<Void> json(ServerHttpResponse response, int status, String json) {
        response.setStatusCode(org.springframework.http.HttpStatus.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8))));
    }

    private static String sse(String event, String data) {
        return "event: " + event + "\n" + "data: " + data.trim() + "\n\n";
    }
}
