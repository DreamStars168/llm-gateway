package com.zhouyu.gateway.routing;

import com.zhouyu.gateway.domain.Channel;
import com.zhouyu.gateway.domain.ModelRoute;
import com.zhouyu.gateway.domain.Protocol;
import com.zhouyu.gateway.routing.RouteTable.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话亲和的路由行为测试。
 *
 * <p>核心断言：同一会话在多次请求中始终落到同一渠道，
 * 即使该渠道处于较低优先级（亲和收益 > 优先级收益）。
 */
class StickyRoutingTest {

    private static Channel channel(long id, String name, int priority, int weight) {
        return new Channel(id, name, Protocol.ANTHROPIC, "http://localhost:9000", "k",
                priority, weight, 100, Duration.ofSeconds(30), true, true);
    }

    private static Candidate candidate(Channel c) {
        return new Candidate(new ModelRoute(c.id(), "m", c, "upstream-m", c.weight(), 1.0), c);
    }

    private static RouteResolver resolver() {
        return new RouteResolver(new SmoothWeightedRoundRobin(), new CircuitBreaker(), new ChannelGates(), new DynamicWeightManager(true, 0.05, 0.5, 0.1, 300));
    }

    @Test
    @DisplayName("无亲和时按优先级选主渠道")
    void noStickyPicksHighestPriority() {
        RouteResolver r = resolver();
        Channel primary = channel(1, "primary", 10, 100);
        Channel backup = channel(2, "backup", 20, 100);
        List<Candidate> cands = List.of(candidate(primary), candidate(backup));

        RouteResolver.Decision d = r.resolve("m", cands, Protocol.ANTHROPIC, Optional.empty());
        assertThat(d.selected().channel().name()).isEqualTo("primary");
        assertThat(d.stickyHit()).isFalse();
        r.release(d);
    }

    @Test
    @DisplayName("有亲和时复用绑定渠道，即使它优先级较低")
    void stickyOverridesPriority() {
        RouteResolver r = resolver();
        Channel primary = channel(1, "primary", 10, 100);
        Channel backup = channel(2, "backup", 20, 100);
        List<Candidate> cands = List.of(candidate(primary), candidate(backup));

        // 会话曾绑定到 backup（低优先级）
        RouteResolver.Decision d = r.resolve("m", cands, Protocol.ANTHROPIC, Optional.of(2L));
        assertThat(d.selected().channel().name()).isEqualTo("backup");
        assertThat(d.stickyHit()).isTrue();
        r.release(d);
    }

    @Test
    @DisplayName("同一会话重复请求始终命中同一渠道（10 次）")
    void sameSessionStaysOnSameChannel() {
        RouteResolver r = resolver();
        Channel a = channel(1, "a", 10, 100);
        Channel b = channel(2, "b", 10, 100);
        List<Candidate> cands = List.of(candidate(a), candidate(b));

        Long bound = 2L;
        for (int i = 0; i < 10; i++) {
            RouteResolver.Decision d = r.resolve("m", cands, Protocol.ANTHROPIC, Optional.of(bound));
            assertThat(d.selected().channel().id()).isEqualTo(bound);
            assertThat(d.stickyHit()).isTrue();
            r.release(d);
        }
    }

    @Test
    @DisplayName("亲和指向的渠道不在候选集时，重新选择且不标记命中")
    void stickyTargetMissing() {
        RouteResolver r = resolver();
        Channel a = channel(1, "a", 10, 100);
        List<Candidate> cands = List.of(candidate(a));

        RouteResolver.Decision d = r.resolve("m", cands, Protocol.ANTHROPIC, Optional.of(999L));
        assertThat(d.selected().channel().id()).isEqualTo(1L);
        assertThat(d.stickyHit()).isFalse();
        assertThat(d.filtered()).containsKey("sticky:999");
        r.release(d);
    }

    @Test
    @DisplayName("亲和渠道熔断时重新选择")
    void stickyTargetBreakerOpen() {
        CircuitBreaker breaker = new CircuitBreaker();
        RouteResolver r = new RouteResolver(new SmoothWeightedRoundRobin(), breaker, new ChannelGates(), new DynamicWeightManager(true, 0.05, 0.5, 0.1, 300));
        Channel a = channel(1, "a", 10, 100);
        Channel b = channel(2, "b", 10, 100);
        List<Candidate> cands = List.of(candidate(a), candidate(b));

        // 打满熔断阈值：20 次失败
        for (int i = 0; i < 25; i++) {
            breaker.recordFailure(2L, "upstream-m");
        }

        RouteResolver.Decision d = r.resolve("m", cands, Protocol.ANTHROPIC, Optional.of(2L));
        assertThat(d.selected().channel().id()).isEqualTo(1L);
        assertThat(d.stickyHit()).isFalse();
        r.release(d);
    }

    @Test
    @DisplayName("亲和命中不得泄漏并发许可")
    void stickyDoesNotLeakPermits() {
        ChannelGates gates = new ChannelGates();
        RouteResolver r = new RouteResolver(new SmoothWeightedRoundRobin(), new CircuitBreaker(), gates, new DynamicWeightManager(true, 0.05, 0.5, 0.1, 300));
        Channel a = channel(1, "a", 10, 100);
        Channel b = channel(2, "b", 10, 100);
        List<Candidate> cands = List.of(candidate(a), candidate(b));

        for (int i = 0; i < 50; i++) {
            RouteResolver.Decision d = r.resolve("m", cands, Protocol.ANTHROPIC, Optional.of(1L));
            r.release(d);
        }
        // 每次只占 1 个、释放 1 个，许可应完全恢复
        assertThat(gates.availablePermits(1)).isEqualTo(100);
        assertThat(gates.availablePermits(2)).isEqualTo(-1); // 从未被占用
    }
}

