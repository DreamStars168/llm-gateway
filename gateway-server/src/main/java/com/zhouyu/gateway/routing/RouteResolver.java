package com.zhouyu.gateway.routing;

import com.zhouyu.gateway.common.ErrorCode;
import com.zhouyu.gateway.common.GatewayException;
import com.zhouyu.gateway.domain.Protocol;
import com.zhouyu.gateway.routing.RouteTable.Candidate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 选路器：候选集 → 协议兼容 → 健康过滤 → 优先级分层 → 加权选择。
 *
 * <p>不负责重试：只负责"给定候选集，选出一个当前最合适的"，
 * 并把被过滤的候选与原因记录进 {@link Decision}，供日志回放。
 */
@Component
public class RouteResolver {

    private final SmoothWeightedRoundRobin swrr;
    private final CircuitBreaker breaker;
    private final ChannelGates gates;
    private final DynamicWeightManager dynamicWeight;

    public RouteResolver(SmoothWeightedRoundRobin swrr, CircuitBreaker breaker, ChannelGates gates,
                         DynamicWeightManager dynamicWeight) {
        this.swrr = swrr;
        this.breaker = breaker;
        this.gates = gates;
        this.dynamicWeight = dynamicWeight;
    }

    /** 计算候选的生效权重（基础权重 × 健康度）。 */
    private int effectiveWeight(Candidate c) {
        return dynamicWeight.effectiveWeight(
                c.channel().id(), c.route().upstreamModel(), c.route().weight());
    }

    /**
     * 释放一次决策占用的并发许可。调用方必须在请求结束时调用，
     * 否则渠道许可会被耗尽。幂等由 Semaphore 语义保证不够，故由调用方保证只调一次。
     */
    public void release(Decision decision) {
        gates.release(decision.selected().channel().id());
    }

    /**
     * 选定一个渠道并占用其并发许可。调用方必须在请求结束时 {@link ChannelGates#release}。
     */
    public Decision resolve(String virtualModel, List<Candidate> candidates, Protocol ingress) {
        return resolve(virtualModel, candidates, ingress, java.util.Optional.empty());
    }

    /**
     * 带会话亲和的选路。
     *
     * @param stickyChannelId 该会话已绑定的渠道；为空表示未绑定或已失效
     *
     * <p><b>亲和优先级最高</b>：即使它在低优先级层，也优先复用——
     * 因为保住上游 prompt cache 的收益（成本可降 5~10 倍）远大于优先级分层的收益。
     * 但仍有前提：该渠道必须健康（未熔断、未打满），否则重选。
     */
    public Decision resolve(String virtualModel, List<Candidate> candidates, Protocol ingress,
                            java.util.Optional<Long> stickyChannelId) {
        if (candidates.isEmpty()) {
            throw new GatewayException(ErrorCode.MODEL_NOT_FOUND,
                    "模型 " + virtualModel + " 无可用渠道");
        }

        List<Candidate> compatible = RouteTable.sameProtocol(candidates, ingress);
        if (compatible.isEmpty()) {
            // 允许跨协议（慢车道），但需要在转发层做翻译；此处退化为全部候选
            compatible = candidates;
        }

        Map<String, String> filtered = new LinkedHashMap<>();

        // ── 1. 亲和优先：命中且健康则直接复用，跳过后续层级选择 ──
        if (stickyChannelId.isPresent()) {
            long wanted = stickyChannelId.get();
            Candidate sticky = compatible.stream()
                    .filter(c -> c.channel().id() == wanted)
                    .findFirst()
                    .orElse(null);

            if (sticky == null) {
                filtered.put("sticky:" + wanted, "NOT_IN_CANDIDATES");
            } else if (!breaker.allowRequest(sticky.channel().id(), sticky.route().upstreamModel())) {
                filtered.put("sticky:" + wanted, "BREAKER_OPEN");
            } else if (!gates.acquire(sticky.channel().id(), sticky.channel().maxInflight())) {
                filtered.put("sticky:" + wanted, "INFLIGHT_FULL");
            } else {
                return new Decision(sticky, List.of(sticky), filtered,
                        sticky.channel().priority(), true);
            }
        }

        // ── 2. 无亲和或亲和失效：按优先级 + 权重重新选择 ──
        List<Candidate> healthy = new ArrayList<>();
        for (Candidate c : compatible) {
            String upstreamModel = c.route().upstreamModel();
            if (!breaker.allowRequest(c.channel().id(), upstreamModel)) {
                filtered.put(c.channel().name(), "BREAKER_OPEN");
                continue;
            }
            if (!gates.acquire(c.channel().id(), c.channel().maxInflight())) {
                filtered.put(c.channel().name(), "INFLIGHT_FULL");
                continue;
            }
            healthy.add(c);
        }

        if (healthy.isEmpty()) {
            throw new GatewayException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "模型 " + virtualModel + " 的渠道均不可用: " + filtered);
        }

        // 优先级硬分层：只保留最高优先级（数值最小）的那一层
        int bestPriority = healthy.stream()
                .mapToInt(c -> c.channel().priority())
                .min()
                .orElseThrow();
        List<Candidate> topTier = healthy.stream()
                .filter(c -> c.channel().priority() == bestPriority)
                .toList();

        // 非本层的已占许可要释放，避免泄漏
        for (Candidate c : healthy) {
            if (c.channel().priority() != bestPriority) {
                gates.release(c.channel().id());
            }
        }

        // 用动态权重参与 SWRR：健康度差的渠道权重被压低，但不会归零（保留探测流量）
        String key = virtualModel + "|" + topTier.stream()
                .map(c -> c.channel().id() + ":" + effectiveWeight(c))
                .sorted()
                .reduce("", (a, b) -> a + "," + b);

        Candidate selected = swrr.select(key, topTier, this::effectiveWeight);

        // 同层中已 acquire 却未被选中的必须释放，否则每次请求都会泄漏一个许可
        for (Candidate c : topTier) {
            if (c != selected) {
                gates.release(c.channel().id());
            }
        }

        return new Decision(selected, List.copyOf(healthy), filtered, bestPriority, false);
    }

    /**
     * 一次路由决策的结果。
     *
     * @param selected    选中的候选（已占用并发许可）
     * @param eligible    通过健康检查的候选
     * @param filtered    被过滤的渠道与原因
     * @param priority    命中的优先级层
     * @param stickyHit   是否命中会话亲和
     */
    public record Decision(
            Candidate selected,
            List<Candidate> eligible,
            Map<String, String> filtered,
            int priority,
            boolean stickyHit
    ) {
        public Map<String, Object> toTrace() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("selected", selected.channel().name());
            m.put("upstreamModel", selected.route().upstreamModel());
            m.put("priority", priority);
            m.put("stickyHit", stickyHit);
            m.put("eligible", eligible.stream().map(c -> c.channel().name()).toList());
            m.put("filtered", filtered);
            return m;
        }
    }
}

