package com.zhouyu.gateway.routing;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 路由表持有者。整表原子替换，读路径无锁。
 *
 * <p>热更新流程：构建新 {@link RouteTable} → {@code swap()} → 旧表由 GC 回收，
 * 正在处理的请求继续持有旧表引用，不会被中途改变。
 */
@Component
public class RouteTableHolder {

    private final AtomicReference<RouteTable> ref = new AtomicReference<>(new RouteTable(0, java.util.List.of(), java.util.List.of()));

    public RouteTable table() {
        return ref.get();
    }

    public void swap(RouteTable next) {
        ref.set(next);
    }

    public long version() {
        return ref.get().version();
    }
}
