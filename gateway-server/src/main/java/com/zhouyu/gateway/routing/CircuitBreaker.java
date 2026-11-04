package com.zhouyu.gateway.routing;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 熔断器，粒度为「渠道 × 上游模型」。
 *
 * <p>状态机：CLOSED → (失败率超阈值) → OPEN → (冷却结束) → HALF_OPEN → (连续成功) → CLOSED。
 * 状态保存在本地，允许跨节点短暂不一致——熔断是保护性措施，不需要强一致。
 */
@Component
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private static final int WINDOW_SECONDS = 10;
    private static final int MIN_REQUESTS = 20;
    private static final double FAILURE_RATE_THRESHOLD = 0.5;
    private static final int HALF_OPEN_PROBES = 3;
    private static final int HALF_OPEN_SUCCESS_NEEDED = 3;
    private static final Duration BASE_COOLDOWN = Duration.ofSeconds(30);

    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();

    public boolean allowRequest(long channelId, String upstreamModel) {
        Breaker b = breakers.computeIfAbsent(key(channelId, upstreamModel), k -> new Breaker());
        return b.allow();
    }

    public void recordSuccess(long channelId, String upstreamModel) {
        breakers.computeIfAbsent(key(channelId, upstreamModel), k -> new Breaker()).onSuccess();
    }

    public void recordFailure(long channelId, String upstreamModel) {
        breakers.computeIfAbsent(key(channelId, upstreamModel), k -> new Breaker()).onFailure();
    }

    public State state(long channelId, String upstreamModel) {
        Breaker b = breakers.get(key(channelId, upstreamModel));
        return b == null ? State.CLOSED : b.currentState();
    }

    private static String key(long channelId, String model) {
        return channelId + ":" + model;
    }

    private static final class Breaker {
        private volatile State state = State.CLOSED;
        private final AtomicLong openedAt = new AtomicLong(0);
        private final AtomicLong failureCount = new AtomicLong();
        private final AtomicLong successCount = new AtomicLong();
        private final AtomicInteger halfOpenProbes = new AtomicInteger();
        private final AtomicInteger halfOpenSuccess = new AtomicInteger();

        synchronized boolean allow() {
            State s = currentState();
            if (s == State.CLOSED) {
                return true;
            }
            if (s == State.OPEN) {
                return false;
            }
            // HALF_OPEN：限制探测并发
            return halfOpenProbes.incrementAndGet() <= HALF_OPEN_PROBES;
        }

        State currentState() {
            if (state == State.OPEN && System.currentTimeMillis() - openedAt.get() > BASE_COOLDOWN.toMillis()) {
                state = State.HALF_OPEN;
                halfOpenProbes.set(0);
                halfOpenSuccess.set(0);
            }
            return state;
        }

        synchronized void onSuccess() {
            if (state == State.HALF_OPEN) {
                if (halfOpenSuccess.incrementAndGet() >= HALF_OPEN_SUCCESS_NEEDED) {
                    state = State.CLOSED;
                    failureCount.set(0);
                    successCount.set(0);
                }
                return;
            }
            successCount.incrementAndGet();
        }

        synchronized void onFailure() {
            if (state == State.HALF_OPEN) {
                state = State.OPEN;
                openedAt.set(System.currentTimeMillis());
                return;
            }
            long total = failureCount.incrementAndGet() + successCount.get();
            if (total >= MIN_REQUESTS && (double) failureCount.get() / total >= FAILURE_RATE_THRESHOLD) {
                state = State.OPEN;
                openedAt.set(System.currentTimeMillis());
            }
        }
    }
}
