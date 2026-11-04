package com.zhouyu.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Mock 上游：模拟 Anthropic Messages 的流式与非流式响应，用于压测与端到端验证。
 */
@SpringBootApplication
public class MockUpstreamApplication {
    public static void main(String[] args) {
        SpringApplication.run(MockUpstreamApplication.class, args);
    }
}
