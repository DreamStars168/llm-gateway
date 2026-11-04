package com.zhouyu.gateway.routing;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 渠道级并发闸门。用 tryAcquire 非阻塞获取，失败即换下一个候选，
 * 绝不排队等待——排队会放大尾延迟。
 */
@Component
public class ChannelGates {

    private final Map<Long, Semaphore> gates = new ConcurrentHashMap<>();

    public boolean acquire(long channelId, int maxInflight) {
        return gates.computeIfAbsent(channelId, k -> new Semaphore(Math.max(1, maxInflight)))
                .tryAcquire();
    }

    public void release(long channelId) {
        Semaphore s = gates.get(channelId);
        if (s != null) {
            s.release();
        }
    }

    public int availablePermits(long channelId) {
        Semaphore s = gates.get(channelId);
        return s == null ? -1 : s.availablePermits();
    }
}
