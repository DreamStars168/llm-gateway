package com.zhouyu.gateway.domain;

/**
 * 虚拟模型到真实上游模型的映射（路由最小单元）。
 *
 * @param id            路由 ID
 * @param virtualModel  对客户端暴露的模型名
 * @param channel       目标渠道
 * @param upstreamModel 上游真实模型名
 * @param weight        层内权重
 * @param priceRatio    渠道倍率（成本修正）
 */
public record ModelRoute(
        long id,
        String virtualModel,
        Channel channel,
        String upstreamModel,
        int weight,
        double priceRatio
) {
}
