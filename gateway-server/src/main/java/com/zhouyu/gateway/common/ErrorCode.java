package com.zhouyu.gateway.common;

/**
 * 网关对外错误码。对外表现为 OpenAI 风格 error.type / error.code。
 */
public enum ErrorCode {

    INVALID_API_KEY(401, "invalid_request_error", "invalid_api_key", "API key 无效或已禁用"),
    INSUFFICIENT_QUOTA(402, "insufficient_quota", "insufficient_quota", "余额或额度不足"),
    MODEL_NOT_FOUND(404, "invalid_request_error", "model_not_found", "模型不存在或无可用渠道"),
    INVALID_REQUEST(400, "invalid_request_error", "invalid_request", "请求参数不合法"),
    CONTEXT_LENGTH_EXCEEDED(400, "invalid_request_error", "context_length_exceeded", "超出模型上下文长度"),
    UPSTREAM_RATE_LIMITED(429, "rate_limit_error", "upstream_rate_limited", "上游限流"),
    UPSTREAM_UNAVAILABLE(503, "server_error", "upstream_unavailable", "上游不可用"),
    UPSTREAM_TIMEOUT(504, "server_error", "upstream_timeout", "上游超时"),
    GATEWAY_OVERLOADED(429, "rate_limit_error", "gateway_overloaded", "网关过载"),
    QUOTA_UNAVAILABLE(503, "server_error", "quota_unavailable", "配额服务不可用"),
    INTERNAL_ERROR(500, "server_error", "internal_error", "网关内部错误");

    private final int httpStatus;
    private final String type;
    private final String code;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String type, String code, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.type = type;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String type() {
        return type;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
