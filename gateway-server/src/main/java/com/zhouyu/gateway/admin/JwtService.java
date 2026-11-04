package com.zhouyu.gateway.admin;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * 管理端 JWT 签发与校验。
 *
 * <p><b>密钥必须通过环境变量 {@code ADMIN_JWT_SECRET} 提供</b>，长度 >= 32 字节。
 * 不提供默认值：使用固定默认密钥等同于没有鉴权（任何人都能伪造 token）。
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long expireMillis;

    public JwtService(@Value("${gateway.admin.jwt-secret:}") String secret,
                      @Value("${gateway.admin.jwt-expire-minutes:120}") long expireMinutes) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("""
                    未配置 JWT 密钥。
                    请设置环境变量 ADMIN_JWT_SECRET（至少 32 字节的随机字符串）后重启。
                    可用以下命令生成：openssl rand -hex 32
                    """);
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("ADMIN_JWT_SECRET 长度不足 32 字节，请重新生成");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireMinutes * 60_000L;
    }

    public String issue(long userId, String username, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(Long.toString(userId))
                .claims(Map.of("username", username, "role", role))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    /**
     * 校验并返回 claims；失败抛异常。
     */
    public Claims verify(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
