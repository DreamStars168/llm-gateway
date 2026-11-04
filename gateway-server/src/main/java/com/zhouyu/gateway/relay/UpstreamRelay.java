package com.zhouyu.gateway.relay;

import com.zhouyu.gateway.domain.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 上游转发管线。
 *
 * <p>两种模式：
 * <ol>
 *   <li><b>透传</b>：同协议，字节级转发，零拷贝、零解析。</li>
 *   <li><b>翻译</b>：跨协议，把上游 SSE 字节流交给 {@code transformer} 转成客户端协议文本。</li>
 * </ol>
 *
 * <p>红线：一旦向客户端写出首字节，任何失败都不再重试。
 */
@Component
public class UpstreamRelay {

    private static final Logger log = LoggerFactory.getLogger(UpstreamRelay.class);

    private final WebClient webClient;

    public UpstreamRelay() {
        ConnectionProvider provider = ConnectionProvider.builder("upstream")
                .maxConnections(2000)
                .pendingAcquireTimeout(Duration.ofSeconds(5))
                .maxIdleTime(Duration.ofSeconds(30))
                .maxLifeTime(Duration.ofMinutes(10))
                .lifo()
                .build();

        HttpClient httpClient = HttpClient.create(provider)
                .responseTimeout(Duration.ofSeconds(600))
                .compress(true)
                .followRedirect(false);

        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * 透传模式：字节原样转发。
     */
    public Mono<Void> relay(ServerHttpRequest inbound,
                            ServerHttpResponse out,
                            Channel channel,
                            String path,
                            byte[] body,
                            boolean stream,
                            FirstByteFlag firstByte,
                            TailUsageSniffer meter) {
        return exchange(inbound, channel, path, body, stream, resp -> {
            copyHeaders(resp.headers().asHttpHeaders(), out.getHeaders());
            if (stream) {
                out.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
                out.getHeaders().setCacheControl("no-cache");
                out.getHeaders().set("X-Accel-Buffering", "no");
            }
            DataBufferFactory factory = out.bufferFactory();
            Flux<DataBuffer> frames = resp.bodyToFlux(DataBuffer.class)
                    .doOnNext(buf -> {
                        firstByte.set();
                        meter.accept(buf);
                    });
            return out.writeWith(frames);
        });
    }

    /**
     * 翻译模式：上游字节流经 {@code transformer} 转换为客户端 SSE 文本。
     *
     * @param transformer 输入上游字节流，输出客户端 SSE 字符串流
     */
    public Mono<Void> relayTranslated(ServerHttpRequest inbound,
                                      ServerHttpResponse out,
                                      Channel channel,
                                      String path,
                                      byte[] body,
                                      boolean stream,
                                      FirstByteFlag firstByte,
                                      TailUsageSniffer meter,
                                      Function<Flux<byte[]>, Flux<String>> transformer) {
        return exchange(inbound, channel, path, body, stream, resp -> {
            out.setStatusCode(HttpStatusCode.valueOf(200));
            out.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
            out.getHeaders().setCacheControl("no-cache");
            out.getHeaders().set("X-Accel-Buffering", "no");

            DataBufferFactory factory = out.bufferFactory();
            Flux<byte[]> raw = resp.bodyToFlux(DataBuffer.class)
                    .map(buf -> {
                        byte[] bytes = new byte[buf.readableByteCount()];
                        buf.read(bytes);
                        org.springframework.core.io.buffer.DataBufferUtils.release(buf);
                        return bytes;
                    });

            return out.writeWith(transformer.apply(raw)
                    .doOnNext(s -> firstByte.set())
                    .map(s -> factory.wrap(s.getBytes(StandardCharsets.UTF_8))));
        });
    }

    /**
     * 非流式翻译：上游 JSON → 客户端协议 JSON。
     */
    public Mono<Void> relayTranslatedNonStream(ServerHttpRequest inbound,
                                               ServerHttpResponse out,
                                               Channel channel,
                                               String path,
                                               byte[] body,
                                               FirstByteFlag firstByte,
                                               TailUsageSniffer meter,
                                               Function<byte[], byte[]> transformer) {
        return exchange(inbound, channel, path, body, false, resp ->
                resp.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(raw -> {
                    firstByte.set();
                    byte[] converted = transformer.apply(raw);
                    meter.parseWhole(converted);
                    out.setStatusCode(HttpStatusCode.valueOf(200));
                    out.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return out.writeWith(Mono.just(out.bufferFactory().wrap(converted)));
                }));
    }

    /** 统一的请求发送与错误处理。 */
    private Mono<Void> exchange(ServerHttpRequest inbound,
                                Channel channel,
                                String path,
                                byte[] body,
                                boolean stream,
                                Function<org.springframework.web.reactive.function.client.ClientResponse, Mono<Void>> handler) {

        String url = channel.resolve(path);

        return webClient.method(HttpMethod.POST)
                .uri(url)
                .headers(h -> {
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.setAccept(stream
                            ? java.util.List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                            : java.util.List.of(MediaType.APPLICATION_JSON));
                    UpstreamHeaders.authHeaders(channel).forEach(h::set);
                    inbound.getHeaders().forEach((name, values) -> {
                        if (UpstreamHeaders.isForwardable(name) && !values.isEmpty()) {
                            h.set(name, values.get(0));
                        }
                    });
                })
                .bodyValue(body)
                .exchangeToMono(resp -> {
                    HttpStatusCode status = resp.statusCode();
                    if (status.isError()) {
                        return resp.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(errBody -> Mono.error(new UpstreamError(status.value(), errBody)));
                    }
                    return handler.apply(resp);
                })
                .onErrorMap(e -> !(e instanceof UpstreamError), e -> {
                    log.warn("upstream relay error, channel={}, msg={}", channel.name(), e.toString());
                    return new UpstreamError(502, e.getMessage() == null ? "upstream error" : e.getMessage());
                });
    }

    private static void copyHeaders(HttpHeaders from, HttpHeaders to) {
        for (String name : new String[]{"content-type", "request-id", "x-request-id", "anthropic-ratelimit-requests-remaining"}) {
            String v = from.getFirst(name);
            if (v != null) {
                to.set(name, v);
            }
        }
    }

    /** 首字节标记：跨重试共享，一旦置位即禁止重试。 */
    public static final class FirstByteFlag {
        private final AtomicBoolean flag = new AtomicBoolean(false);
        public void set() { flag.set(true); }
        public boolean isSet() { return flag.get(); }
    }

    /** 上游错误。 */
    public static final class UpstreamError extends RuntimeException {
        private final int status;
        private final String body;
        public UpstreamError(int status, String message) {
            super(message);
            this.status = status;
            this.body = message;
        }
        public int status() { return status; }
        public String body() { return body; }
        public boolean retryable() { return status == 429 || status >= 500; }
    }
}
