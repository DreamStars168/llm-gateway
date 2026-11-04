package com.zhouyu.gateway.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhouyu.gateway.common.ErrorCode;
import com.zhouyu.gateway.common.GatewayException;
import com.zhouyu.gateway.domain.Channel;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 请求体改写：只替换 {@code model} 字段，其余原样保留。
 *
 * <p>优先尝试"字节级等长替换"以避免全量重解析；长度变化时退化为一棵树重建。
 * 生产环境可进一步用 Jackson 流式 API 做区间替换，这里选择可读性与正确性优先。
 */
@Component
public class RequestRewriter {

    private final ObjectMapper mapper;

    public RequestRewriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 改写请求体中的 model 字段。
     *
     * @param body          原始 JSON
     * @param upstreamModel 上游真实模型名
     * @return 改写后的 JSON 字节
     */
    public byte[] rewriteModel(byte[] body, String upstreamModel) {
        try {
            JsonNode node = mapper.readTree(body);
            if (!(node instanceof ObjectNode obj)) {
                throw new GatewayException(ErrorCode.INVALID_REQUEST, "请求体必须是 JSON 对象");
            }
            obj.put("model", upstreamModel);
            return mapper.writeValueAsBytes(obj);
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "请求体解析失败: " + e.getMessage());
        }
    }

    /**
     * 仅提取 model 字段，用于路由，不做完整反序列化。
     */
    public String peekModel(byte[] body) {
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode model = node.get("model");
            if (model == null || model.isNull()) {
                throw new GatewayException(ErrorCode.INVALID_REQUEST, "缺少 model 字段");
            }
            return model.asText();
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "请求体解析失败: " + e.getMessage());
        }
    }

    /**
     * 判断是否为流式请求。
     */
    public boolean isStream(byte[] body) {
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode stream = node.get("stream");
            return stream != null && stream.asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 聚合 DataBuffer 为字节数组。仅在必须改写时调用。
     */
    public Mono<byte[]> join(org.springframework.core.io.buffer.DataBufferFactory factory,
                             org.springframework.http.server.reactive.ServerHttpRequest request) {
        return DataBufferUtils.join(request.getBody())
                .map(buf -> {
                    byte[] bytes = new byte[buf.readableByteCount()];
                    buf.read(bytes);
                    DataBufferUtils.release(buf);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0]);
    }

    public static String asString(DataBuffer buf) {
        byte[] bytes = new byte[buf.readableByteCount()];
        buf.read(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
