package com.zhouyu.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * 安全配置。
 *
 * <p>边界划分：
 * <ul>
 *   <li>{@code /v1/**}：数据面，用 API Key 鉴权（由 WebFilter/Controller 处理），
 *       必须<b>关闭 CSRF</b>——它是给浏览器表单设计的，API 客户端没有 token。</li>
 *   <li>{@code /admin/**}：管理面，走 JWT。</li>
 *   <li>{@code /actuator/**}：运维端点，内网放行（生产应加 IP 白名单）。</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                // API 客户端不使用 Cookie，CSRF 保护无意义且会拦截所有 POST
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(ex -> ex
                        .pathMatchers("/v1/**", "/health", "/ready").permitAll()
                        .pathMatchers("/actuator/**").permitAll()
                        // 管理端鉴权由 AdminAuthFilter(WebFilter) 负责，
                        // 这里必须 permitAll，否则 Security 会在过滤器之前用 401 拦截掉。
                        .pathMatchers("/admin/**").permitAll()
                        .anyExchange().permitAll())
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
