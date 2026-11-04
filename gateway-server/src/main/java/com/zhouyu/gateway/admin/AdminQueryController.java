package com.zhouyu.gateway.admin;

import com.zhouyu.gateway.domain.Channel;
import com.zhouyu.gateway.domain.ModelRoute;
import com.zhouyu.gateway.routing.CircuitBreaker;
import com.zhouyu.gateway.routing.RouteTable;
import com.zhouyu.gateway.routing.RouteTableHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读的管理端查询 API。
 *
 * <p>M6 阶段先提供「看得见」的能力：渠道健康、模型路由、系统概览。
 * 写操作（增删改渠道）在下一迭代接入，需配合配置版本与热更新。
 */
@RestController
@RequestMapping("/admin/api/v1")
public class AdminQueryController {

    private final RouteTableHolder tables;
    private final CircuitBreaker breaker;

    public AdminQueryController(RouteTableHolder tables, CircuitBreaker breaker) {
        this.tables = tables;
        this.breaker = breaker;
    }

    /** 系统概览。 */
    @GetMapping("/overview")
    public ResponseEntity<?> overview() {
        RouteTable table = tables.table();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", table.version());
        out.put("models", table.knownModels().size());
        out.put("channels", collectChannels(table).size());
        return ResponseEntity.ok(out);
    }

    /** 渠道列表（含熔断状态）。 */
    @GetMapping("/channels")
    public ResponseEntity<?> channels() {
        RouteTable table = tables.table();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Channel c : collectChannels(table)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("name", c.name());
            m.put("protocol", c.protocol().name());
            m.put("baseUrl", c.baseUrl());
            m.put("priority", c.priority());
            m.put("weight", c.weight());
            m.put("maxInflight", c.maxInflight());
            m.put("passthrough", c.passthrough());
            m.put("enabled", c.enabled());
            list.add(m);
        }
        return ResponseEntity.ok(Map.of("data", list));
    }

    /** 虚拟模型与其路由。 */
    @GetMapping("/models")
    public ResponseEntity<?> models() {
        RouteTable table = tables.table();
        List<Map<String, Object>> list = new ArrayList<>();
        for (String name : table.knownModels()) {
            List<Map<String, Object>> routes = new ArrayList<>();
            for (var c : table.candidates(name)) {
                ModelRoute r = c.route();
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("channelId", c.channel().id());
                rm.put("channelName", c.channel().name());
                rm.put("upstreamModel", r.upstreamModel());
                rm.put("priority", c.channel().priority());
                rm.put("weight", r.weight());
                rm.put("protocol", c.channel().protocol().name());
                routes.add(rm);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("routes", routes);
            list.add(m);
        }
        return ResponseEntity.ok(Map.of("data", list));
    }

    /** 单个模型的熔断状态。 */
    @GetMapping("/breaker/{channelId}/{model}")
    public ResponseEntity<?> breakerState(@PathVariable long channelId, @PathVariable String model) {
        return ResponseEntity.ok(Map.of(
                "channelId", channelId,
                "model", model,
                "state", breaker.state(channelId, model).name()));
    }

    private static List<Channel> collectChannels(RouteTable table) {
        Map<Long, Channel> byId = new LinkedHashMap<>();
        for (String model : table.knownModels()) {
            for (var c : table.candidates(model)) {
                byId.putIfAbsent(c.channel().id(), c.channel());
            }
        }
        return new ArrayList<>(byId.values());
    }
}
