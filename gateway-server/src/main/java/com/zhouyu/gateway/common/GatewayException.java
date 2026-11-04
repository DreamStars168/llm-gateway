package com.zhouyu.gateway.common;

/**
 * 网关业务异常。携带稳定错误码，由全局异常处理器统一渲染。
 */
public class GatewayException extends RuntimeException {

    private final ErrorCode errorCode;
    private final boolean retryable;

    public GatewayException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), false);
    }

    public GatewayException(ErrorCode errorCode, String message) {
        this(errorCode, message, false);
    }

    public GatewayException(ErrorCode errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 是否允许切换渠道重试。仅当尚未向客户端写出任何字节时才有意义。
     */
    public boolean retryable() {
        return retryable;
    }

    public static GatewayException invalidRequest(String message) {
        return new GatewayException(ErrorCode.INVALID_REQUEST, message);
    }

    public static GatewayException upstream(int status, String message) {
        boolean retryable = status == 429 || status >= 500;
        ErrorCode code = status == 429 ? ErrorCode.UPSTREAM_RATE_LIMITED : ErrorCode.UPSTREAM_UNAVAILABLE;
        return new GatewayException(code, message, retryable);
    }
}
