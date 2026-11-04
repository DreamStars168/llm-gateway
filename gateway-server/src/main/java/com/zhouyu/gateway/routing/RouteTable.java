package com.zhouyu.gateway.routing;

import com.zhouyu.gateway.domain.ApiKeyView;
import com.zhouyu.gateway.domain.Channel;
import com.zhouyu.gateway.domain.ModelRoute;
import com.zhouyu.gateway.domain.Protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 不可变路由表快照。整表通过 AtomicReference 整体替换实现热更新，
 * 读路径无锁、无迭代器并发问题。
 */
public final class RouteTable {

    private final long version;
    private final Map<String, List<ModelRoute>> routesByModel;
    private final Map<String, ApiKeyView> keysByHash;

    public RouteTable(long version, List<ModelRoute> routes, List<ApiKeyView> keys) {
        this.version = version;
        Map<String, List<ModelRoute>> byModel = new HashMap<>();
        for (ModelRoute r : routes) {
            byModel.computeIfAbsent(r.virtualModel(), k -> new ArrayList<>()).add(r);
        }
        // 按优先级升序，同优先级内保持声明顺序，便于稳定断言
        byModel.replaceAll((k, v) -> v.stream()
                .sorted(Comparator.comparingInt(r -> r.channel().priority()))
                .toList());
        this.routesByModel = Map.copyOf(byModel);

        Map<String, ApiKeyView> byHash = keys.stream()
                .collect(Collectors.toMap(ApiKeyView::keyHash, k -> k, (a, b) -> a));
        this.keysByHash = Map.copyOf(byHash);
    }

    public long version() {
        return version;
    }

    public Optional<ApiKeyView> findKey(String rawSecret) {
        return Optional.ofNullable(keysByHash.get(sha256(rawSecret)));
    }

    /**
     * 构建候选集：模型存在 + 渠道启用 + 协议可承接。
     */
    public List<Candidate> candidates(String virtualModel) {
        List<ModelRoute> routes = routesByModel.get(virtualModel);
        if (routes == null || routes.isEmpty()) {
            return List.of();
        }
        return routes.stream()
                .filter(r -> r.channel().enabled())
                .map(r -> new Candidate(r, r.channel()))
                .toList();
    }

    public Set<String> knownModels() {
        return routesByModel.keySet();
    }

    /**
     * 只保留与入口协议一致的候选，用于"快车道"透传判定。
     */
    public static List<Candidate> sameProtocol(List<Candidate> candidates, Protocol ingress) {
        return candidates.stream()
                .filter(c -> c.channel().protocol() == ingress)
                .toList();
    }

    /**
     * 候选：路由 + 渠道的组合。
     */
    public record Candidate(ModelRoute route, Channel channel) {
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
