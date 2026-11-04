package com.zhouyu.gateway.admin;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 管理端 JWT 过滤器。
 *
 * <p>只拦截 {@code /admin/**}（登录接口除外）。数据面 {@code /v1/**} 不经过这里，
 * 避免给高并发路径增加任何判断开销。
 */
@Component
@Order(-100)
public class AdminAuthFilter implements WebFilter {

    private static final List<String> WHITELIST = List.of(
            "/admin/api/v1/auth/login",
            "/admin/api/v1/auth/verify");

    private final JwtService jwt;

    public AdminAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/admin/")) {
            return chain.filter(exchange);
        }
        if (WHITELIST.contains(path)) {
            return chain.filter(exchange);
        }

        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            return unauthorized(exchange, "缺少 Bearer token");
        }
        try {
            var claims = jwt.verify(auth.substring(7));
            exchange.getAttributes().put("adminUser", claims.get("username"));
            return chain.filter(exchange);
        } catch (Exception e) {
            // 记录真实原因，便于排查（对外只返回笼统信息）
            org.slf4j.LoggerFactory.getLogger(AdminAuthFilter.class)
                    .warn("JWT 校验失败 path={} reason={}", path, e.toString());
            return unauthorized(exchange, "token 无效或已过期: " + e.getClass().getSimpleName());
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String msg) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");
        byte[] body = ("{\"error\":{\"message\":\"" + msg + "\",\"code\":\"unauthorized\"}}").getBytes();
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
