package com.zhouyu.gateway.domain;

import java.util.Set;

/**
 * 客户端 API Key 的鉴权视图（只含校验所需的非敏感字段）。
 *
 * @param id             Key ID
 * @param keyHash        sha256(secret) 的十六进制
 * @param tenantId       租户 ID
 * @param groupId        分组 ID
 * @param allowedModels  允许的虚拟模型，null 或空表示全部
 * @param rpmLimit       每分钟请求上限，null 表示不限
 * @param dailyQuotaMicro 日额度（micro-USD），null 表示不限
 */
public record ApiKeyView(
        long id,
        String keyHash,
        long tenantId,
        long groupId,
        Set<String> allowedModels,
        Integer rpmLimit,
        Long dailyQuotaMicro
) {

    public boolean allowsModel(String virtualModel) {
        return allowedModels == null || allowedModels.isEmpty() || allowedModels.contains(virtualModel);
    }
}
