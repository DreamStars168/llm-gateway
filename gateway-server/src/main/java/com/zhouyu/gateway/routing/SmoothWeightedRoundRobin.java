package com.zhouyu.gateway.routing;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 平滑加权轮询（Smooth Weighted Round-Robin）。
 *
 * <p>相比朴素轮询，避免"权重 5:1 时前 5 个请求全打 A"的突发问题；
 * 状态保存在本地，多实例天然分摊，无需 Redis。
 */
@Component
public class SmoothWeightedRoundRobin {

    private final Map<String, List<Node>> states = new ConcurrentHashMap<>();

    /**
     * 在候选列表中按权重选出一个。调用方需保证 candidates 非空。
     *
     * @param key        分组键（通常是 virtualModel + 候选集合指纹），隔离不同模型的计数
     * @param candidates 已按优先级分层的候选
     */
    public <T> T select(String key, List<T> candidates, java.util.function.ToIntFunction<T> weigher) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        List<Node> nodes = states.computeIfAbsent(key, k -> {
            List<Node> list = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                list.add(new Node());
            }
            return list;
        });

        // 候选数量变化（热更新）时重建，避免越界
        if (nodes.size() != candidates.size()) {
            List<Node> rebuilt = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                rebuilt.add(new Node());
            }
            states.put(key, rebuilt);
            nodes = rebuilt;
        }

        int total = 0;
        int bestIdx = 0;
        int bestCurrent = Integer.MIN_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            int w = Math.max(1, weigher.applyAsInt(candidates.get(i)));
            total += w;
            Node n = nodes.get(i);
            n.current.addAndGet(w);
            int cur = n.current.get();
            if (cur > bestCurrent) {
                bestCurrent = cur;
                bestIdx = i;
            }
        }
        nodes.get(bestIdx).current.addAndGet(-total);
        return candidates.get(bestIdx);
    }

    private static final class Node {
        final AtomicInteger current = new AtomicInteger(0);
    }
}
