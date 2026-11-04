package com.zhouyu.gateway.protocol.canonical;

import java.util.List;

/**
 * 采样参数。null 表示客户端未指定，转换时由出口适配器决定是否填默认值。
 */
public record GenerationParams(
        Double temperature,
        Double topP,
        Integer topK,
        Integer maxTokens,
        List<String> stop,
        Double presencePenalty,
        Double frequencyPenalty,
        Long seed
) {
    public static GenerationParams defaults() {
        return new GenerationParams(null, null, null, null, null, null, null, null);
    }

    public GenerationParams withMaxTokens(Integer v) {
        return new GenerationParams(temperature, topP, topK, v, stop, presencePenalty, frequencyPenalty, seed);
    }
}
