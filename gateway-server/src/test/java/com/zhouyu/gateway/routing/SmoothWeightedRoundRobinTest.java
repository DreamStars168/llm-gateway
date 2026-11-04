package com.zhouyu.gateway.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmoothWeightedRoundRobinTest {

    @Test
    @DisplayName("权重 5:1 时分布平滑，不会连续 5 次都打 A")
    void distributionIsSmooth() {
        SmoothWeightedRoundRobin swrr = new SmoothWeightedRoundRobin();
        int a = 0;
        int b = 0;
        for (int i = 0; i < 60; i++) {
            String picked = swrr.select("k", java.util.List.of("A", "B"), x -> "A".equals(x) ? 5 : 1);
            if ("A".equals(picked)) {
                a++;
            } else {
                b++;
            }
        }
        assertThat(a).isEqualTo(50);
        assertThat(b).isEqualTo(10);
    }

    @Test
    @DisplayName("单候选直接返回，不维护状态")
    void singleCandidate() {
        SmoothWeightedRoundRobin swrr = new SmoothWeightedRoundRobin();
        for (int i = 0; i < 10; i++) {
            assertThat(swrr.select("k", java.util.List.of("only"), x -> 100)).isEqualTo("only");
        }
    }
}

