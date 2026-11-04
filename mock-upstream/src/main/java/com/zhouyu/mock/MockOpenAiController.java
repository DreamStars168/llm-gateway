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
import java.util.UUID;

/**
 * 模拟 OpenAI Chat Completions，用于验证「Anthropic 入口 → OpenAI 渠道」的翻译链路。
 */
@RestController
public class MockOpenAiController {

    private final ObjectMapper mapper = new ObjectMapper();

    @PostMapping("/v1/chat/completions")
    public Mono<Void> chat(@RequestBody String body,
                           @RequestHeader(value = "x-mock-status", required = false) Integer mockStatus,
                           @RequestHeader(value = "x-mock-delay-ms", required = false) Integer delayMs,
                           ServerHttpResponse response) {

        if (mockStatus != null && mockStatus >= 400) {
            return json(response, mockStatus,
                    "{\"error\":{\"type\":\"mock_error\",\"message\":\"forced " + mockStatus + "\"}}");
        }

        JsonNode req;
        try {
            req = mapper.readTree(body);
        } catch (Exception e) {
            return json(response, 400, "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad json\"}}");
        }

        boolean stream = req.path("stream").asBoolean(false);
        String model = req.path("model").asText("unknown");
        String id = "chatcmpl-mock-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long created = System.currentTimeMillis() / 1000;

        if (!stream) {
            String out = """
                    {"id":"%s","object":"chat.completion","created":%d,"model":"%s",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"Hello from OpenAI-compatible mock."},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":31,"completion_tokens":11,"total_tokens":42}}
                    """.formatted(id, created, model);
            return json(response, 200, out);
        }

        int chunks = 6;
        long gap = delayMs == null ? 30L : delayMs;
        DataBufferFactory f = response.bufferFactory();
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
        response.getHeaders().set("Cache-Control", "no-cache");

        Flux<String> frames = Flux.concat(
                Mono.just(chunk(id, created, model, "{\"role\":\"assistant\",\"content\":\"\"}", null)),
                Flux.range(1, chunks)
                        .delayElements(Duration.ofMillis(gap))
                        .map(i -> chunk(id, created, model, "{\"content\":\"word" + i + " \"}", null)),
                Mono.just(chunk(id, created, model, "{}", "stop")),
                // stream_options.include_usage 时，最后一帧带 usage
                Mono.just("data: {\"id\":\"" + id + "\",\"object\":\"chat.completion.chunk\",\"created\":" + created
                        + ",\"model\":\"" + model + "\",\"choices\":[],\"usage\":{\"prompt_tokens\":31,\"completion_tokens\":12,\"total_tokens\":43}}\n\n"),
                Mono.just("data: [DONE]\n\n")
        );

        return response.writeWith(frames.map(s -> f.wrap(s.getBytes(StandardCharsets.UTF_8))));
    }

    private String chunk(String id, long created, String model, String deltaJson, String finish) {
        String fr = finish == null ? "null" : "\"" + finish + "\"";
        return "data: {\"id\":\"" + id + "\",\"object\":\"chat.completion.chunk\",\"created\":" + created
                + ",\"model\":\"" + model + "\",\"choices\":[{\"index\":0,\"delta\":" + deltaJson
                + ",\"finish_reason\":" + fr + "}]}\n\n";
    }

    private Mono<Void> json(ServerHttpResponse response, int status, String json) {
        response.setStatusCode(HttpStatus.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8))));
    }
}
