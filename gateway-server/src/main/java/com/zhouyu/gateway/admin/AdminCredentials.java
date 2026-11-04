package com.zhouyu.gateway.admin;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 管理端凭证。
 *
 * <p><b>安全要求</b>：用户名与密码必须通过环境变量注入，本类<b>不提供默认密码</b>。
 * 未配置时启动直接失败（fail-fast），而不是静默使用一个公开的弱密码。
 *
 * <p>密码支持两种形式：
 * <ul>
 *   <li>明文：启动时用 BCrypt 哈希后驻留内存（方便本地开发）</li>
 *   <li>BCrypt 哈希（以 {@code $2a$/$2b$/$2y$} 开头）：直接比对，不落明文</li>
 * </ul>
 */
@Component
public class AdminCredentials {

    private static final Logger log = LoggerFactory.getLogger(AdminCredentials.class);

    private final String username;
    private final String passwordOrHash;
    private final PasswordEncoder encoder;

    private String hashed;

    public AdminCredentials(
            @Value("${gateway.admin.username:}") String username,
            @Value("${gateway.admin.password:}") String passwordOrHash,
            PasswordEncoder encoder) {
        this.username = username;
        this.passwordOrHash = passwordOrHash;
        this.encoder = encoder;
    }

    @PostConstruct
    void validate() {
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("""
                    未配置管理端用户名。
                    请设置环境变量 ADMIN_USERNAME（例如 admin）后重启。
                    """);
        }
        if (passwordOrHash == null || passwordOrHash.isBlank()) {
            throw new IllegalStateException("""
                    未配置管理端密码。
                    请设置环境变量 ADMIN_PASSWORD 后重启。
                    不要使用弱密码；生产环境建议直接提供 BCrypt 哈希。
                    """);
        }
        if (passwordOrHash.length() < 8) {
            throw new IllegalStateException("管理端密码长度不得少于 8 位");
        }
        this.hashed = isBcrypt(passwordOrHash) ? passwordOrHash : encoder.encode(passwordOrHash);
        log.info("管理端账号已加载: username={}（密码来源: {}）",
                username, isBcrypt(passwordOrHash) ? "BCrypt 哈希" : "明文（已在内存哈希）");
    }

    public boolean matches(String inputUser, String inputPassword) {
        if (!username.equals(inputUser)) {
            // 即使用户名错也执行一次哈希比对，避免通过响应时间探测用户名是否存在
            encoder.matches(inputPassword, hashed);
            return false;
        }
        return encoder.matches(inputPassword, hashed);
    }

    private static boolean isBcrypt(String s) {
        return s.startsWith("$2a$") || s.startsWith("$2b$") || s.startsWith("$2y$");
    }
}
