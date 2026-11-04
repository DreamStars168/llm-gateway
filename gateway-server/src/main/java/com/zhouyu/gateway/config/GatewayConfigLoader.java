package com.zhouyu.gateway.config;

import com.zhouyu.gateway.domain.ApiKeyView;
import com.zhouyu.gateway.domain.Channel;
import com.zhouyu.gateway.domain.ModelRoute;
import com.zhouyu.gateway.domain.Protocol;
import com.zhouyu.gateway.routing.RouteTable;
import com.zhouyu.gateway.routing.RouteTableHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 启动时把配置装载为不可变路由表快照。
 *
 * <p>M1 阶段数据源是 application.yml；接入 MySQL 后只需替换 {@link #buildTable}
 * 的入参来源，数据面代码完全不动。
 */
@Configuration
public class GatewayConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfigLoader.class);

    @Bean
    ApplicationRunner loadRouteTable(GatewayProperties props, RouteTableHolder holder) {
        return args -> {
            RouteTable table = buildTable(props);
            holder.swap(table);
            log.info("route table loaded: version={} models={} keys={}",
                    table.version(), table.knownModels(), props.getKeys().size());
        };
    }

    static RouteTable buildTable(GatewayProperties props) {
        List<Channel> channels = new ArrayList<>();
        for (GatewayProperties.ChannelConfig c : props.getChannels()) {
            channels.add(new Channel(
                    c.getId(), c.getName(), Protocol.of(c.getProtocol()), c.getBaseUrl(), c.getApiKey(),
                    c.getPriority(), c.getWeight(), c.getMaxInflight(), c.getTimeout(), c.isPassthrough(), c.isEnabled(), c.isApiKeyInQuery()));
        }

        List<ModelRoute> routes = new ArrayList<>();
        for (GatewayProperties.RouteConfig r : props.getRoutes()) {
            Channel ch = channels.stream()
                    .filter(c -> c.id() == r.getChannelId())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("路由引用了不存在的渠道: " + r.getChannelId()));
            routes.add(new ModelRoute(r.getVirtualModel().hashCode() & 0x7fffffff, r.getVirtualModel(),
                    ch, r.getUpstreamModel(), r.getWeight(), r.getPriceRatio()));
        }

        List<ApiKeyView> keys = new ArrayList<>();
        for (GatewayProperties.KeyConfig k : props.getKeys()) {
            if (k.getSecret() == null || k.getSecret().isBlank()) {
                continue;
            }
            Set<String> allowed = k.getAllowedModels() == null ? Set.of() : new HashSet<>(k.getAllowedModels());
            keys.add(new ApiKeyView(k.getId(), RouteTable.sha256(k.getSecret()), k.getTenantId(),
                    k.getGroupId(), allowed, k.getRpmLimit(), k.getBalanceMicro()));
        }

        return new RouteTable(1, routes, keys);
    }
}

