package com.zhouyu.gateway.protocol.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhouyu.gateway.common.ErrorCode;
import com.zhouyu.gateway.common.GatewayException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误响应渲染。
 *
 * <p>以 OpenAI 风格错误体输出（{@code error.type / error.code / error.message}），
 * 主流客户端 SDK 都能正确识别；Anthropic 客户端同样可解析该结构。
 */
final class ErrorResponses {

    private ErrorResponses() {
    }

    static Mono<Void> write(ServerHttpResponse response, GatewayException e, ObjectMapper mapper) {
        ErrorCode code = e.errorCode();
        response.setStatusCode(HttpStatus.valueOf(code.httpStatus()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", e.getMessage() == null ? code.defaultMessage() : e.getMessage());
        error.put("type", code.type());
        error.put("code", code.code());
        error.put("param", null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("request_id", response.getHeaders().getFirst("x-gateway-request-id"));

        try {
            byte[] bytes = mapper.writeValueAsBytes(body);
            DataBuffer buf = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buf));
        } catch (Exception ex) {
            byte[] fallback = ("{\"error\":{\"message\":\"" + code.code() + "\",\"type\":\"" + code.type() + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(fallback)));
        }
    }
}
