package com.zhouyu.gateway.routing;

import com.zhouyu.gateway.domain.Channel;
import com.zhouyu.gateway.domain.ModelRoute;
import com.zhouyu.gateway.domain.Protocol;
import com.zhouyu.gateway.routing.RouteTable.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteResolverTest {

    private static Channel channel(long id, String name, int priority, int weight, int maxInflight) {
        return new Channel(id, name, Protocol.ANTHROPIC, "http://localhost:9000", "k",
                priority, weight, maxInflight, Duration.ofSeconds(30), true, true);
    }

    private static Candidate candidate(Channel c, String model, int weight) {
        return new Candidate(new ModelRoute(c.id(), "claude-sonnet-4", c, model, weight, 1.0), c);
    }

    @Test
    @DisplayName("优先级硬分层：只在高优先级层内选择")
    void priorityIsHardTier() {
        ChannelGates gates = new ChannelGates();
        RouteResolver resolver = new RouteResolver(new SmoothWeightedRoundRobin(), new CircuitBreaker(), gates, new DynamicWeightManager(true, 0.05, 0.5, 0.1, 300));

        Channel primary = channel(1, "primary", 10, 100, 10);
        Channel backup = channel(2, "backup", 20, 100, 10);

        RouteResolver.Decision d = resolver.resolve("claude-sonnet-4",
                List.of(candidate(primary, "m1", 100), candidate(backup, "m2", 100)),
                Protocol.ANTHROPIC);

        assertThat(d.selected().channel().name()).isEqualTo("primary");
        gates.release(d.selected().channel().id());
    }

    @Test
    @DisplayName("同层多候选：未选中者的并发许可必须被释放，不能泄漏")
    void unselectedSameTierPermitsAreReleased() {
        ChannelGates gates = new ChannelGates();
        RouteResolver resolver = new RouteResolver(new SmoothWeightedRoundRobin(), new CircuitBreaker(), gates, new DynamicWeightManager(true, 0.05, 0.5, 0.1, 300));

        Channel a = channel(11, "a", 10, 100, 1);
        Channel b = channel(12, "b", 10, 100, 1);
        List<Candidate> candidates = List.of(candidate(a, "m", 100), candidate(b, "m", 100));

        // 连续请求 20 次，每次只释放被选中者；若未选中者泄漏，第 2 次就会 INFLIGHT_FULL
        for (int i = 0; i < 20; i++) {
            RouteResolver.Decision d = resolver.resolve("claude-sonnet-4", candidates, Protocol.ANTHROPIC);
            gates.release(d.selected().channel().id());
        }

        assertThat(gates.availablePermits(11)).isEqualTo(1);
        assertThat(gates.availablePermits(12)).isEqualTo(1);
    }

    @Test
    @DisplayName("渠道并发打满时换下一个候选，而不是排队")
    void fullChannelFallsThrough() {
        ChannelGates gates = new ChannelGates();
        RouteResolver resolver = new RouteResolver(new SmoothWeightedRoundRobin(), new CircuitBreaker(), gates, new DynamicWeightManager(true, 0.05, 0.5, 0.1, 300));

        Channel a = channel(21, "a", 10, 100, 1);
        Channel b = channel(22, "b", 10, 100, 1);
        List<Candidate> candidates = List.of(candidate(a, "m", 100), candidate(b, "m", 100));

        RouteResolver.Decision first = resolver.resolve("claude-sonnet-4", candidates, Protocol.ANTHROPIC);
        // 占住第一个被选中的渠道
        String held = first.selected().channel().name();

        RouteResolver.Decision second = resolver.resolve("claude-sonnet-4", candidates, Protocol.ANTHROPIC);
        assertThat(second.selected().channel().name()).isNotEqualTo(held);

        gates.release(first.selected().channel().id());
        gates.release(second.selected().channel().id());
    }

    @Test
    @DisplayName("无候选时抛出 model_not_found")
    void noCandidates() {
        RouteResolver resolver = new RouteResolver(new SmoothWeightedRoundRobin(), new CircuitBreaker(), new ChannelGates(), new DynamicWeightManager(true, 0.05, 0.5, 0.1, 300));
        assertThatThrownBy(() -> resolver.resolve("unknown", List.of(), Protocol.ANTHROPIC))
                .hasMessageContaining("无可用渠道");
    }
}

