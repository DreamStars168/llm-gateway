package com.zhouyu.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * LLM 多模型接入网关入口。
 *
 * <p>数据面（/v1/**）与管理面（/admin/**）共用进程，但线程模型严格隔离：
 * 数据面全部运行在 Reactor Netty event loop 上，禁止任何阻塞调用。
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.zhouyu.gateway")
@org.springframework.scheduling.annotation.EnableScheduling
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}

