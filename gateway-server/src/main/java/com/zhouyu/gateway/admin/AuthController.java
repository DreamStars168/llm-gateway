package com.zhouyu.gateway.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 管理端登录。
 *
 * <p><b>凭证来源</b>：必须通过环境变量 {@code ADMIN_USERNAME} / {@code ADMIN_PASSWORD}
 * 提供，<b>不提供任何默认密码</b>——避免仓库里的演示密码被直接用到生产。
 * 未设置时应用启动失败并给出明确提示（见 {@link AdminCredentials}）。
 *
 * <p>生产环境建议进一步替换为查 {@code sys_user} 表 + BCrypt 校验 + 多因素认证。
 */
@RestController
@RequestMapping("/admin/api/v1/auth")
public class AuthController {

    private final JwtService jwt;
    private final AdminCredentials credentials;
    private final PasswordEncoder passwordEncoder;

    public AuthController(JwtService jwt, AdminCredentials credentials, PasswordEncoder passwordEncoder) {
        this.jwt = jwt;
        this.credentials = credentials;
        this.passwordEncoder = passwordEncoder;
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, String username, String role, long expiresIn) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (req.username() == null || req.password() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名或密码为空");
        }
        if (!credentials.matches(req.username(), req.password())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        String token = jwt.issue(1L, req.username(), "SUPER_ADMIN");
        return ResponseEntity.ok(new LoginResponse(token, req.username(), "SUPER_ADMIN", 7200));
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody Map<String, String> body) {
        try {
            var claims = jwt.verify(body.get("token"));
            return ResponseEntity.ok(Map.of("valid", true, "username", claims.get("username")));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("valid", false));
        }
    }
}
