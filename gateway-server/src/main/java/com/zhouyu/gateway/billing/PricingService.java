package com.zhouyu.gateway.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定价服务：加载并缓存 {@link PriceRule}。
 *
 * <p>本期从 classpath 的 {@code pricing.json} 加载（与配置同源，便于演示与版本控制）；
 * 后续可切换为 MySQL {@code price_rule} 表 + 热刷新，接口不变。
 *
 * <p>缓存策略：启动时一次性加载为不可变 Map，查询零锁零拷贝。
 * 定价变更频率极低（每天几次），重启生效可接受；需要热更新时再引入版本号。
 */
@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    private final ObjectMapper mapper;
    private final String pricingFile;
    private volatile Map<String, PriceRule> rules = Map.of();

    public PricingService(ObjectMapper mapper,
                          @Value("${gateway.pricing.file:pricing.json}") String pricingFile) {
        this.mapper = mapper;
        this.pricingFile = pricingFile;
    }

    @PostConstruct
    public void load() {
        try (InputStream in = new ClassPathResource(pricingFile).getInputStream()) {
            JsonNode root = mapper.readTree(in);
            Map<String, PriceRule> loaded = new HashMap<>();
            JsonNode models = root.get("models");
            if (models != null && models.isObject()) {
                var it = models.fields();
                while (it.hasNext()) {
                    var entry = it.next();
                    loaded.put(entry.getKey(), parseRule(entry.getKey(), entry.getValue()));
                }
            }
            this.rules = Map.copyOf(loaded);
            log.info("定价规则已加载: {} 个模型", rules.size());
        } catch (Exception e) {
            log.error("定价文件 {} 加载失败，所有请求将按 0 成本计费", pricingFile, e);
            this.rules = Map.of();
        }
    }

    private PriceRule parseRule(String model, JsonNode node) {
        List<PriceRule.Tier> tiers = new ArrayList<>();
        JsonNode tierArr = node.get("tiers");
        if (tierArr != null && tierArr.isArray()) {
            for (JsonNode t : tierArr) {
                tiers.add(new PriceRule.Tier(
                        t.has("inputTokensLte") && !t.get("inputTokensLte").isNull()
                                ? t.get("inputTokensLte").asLong() : null,
                        dec(t, "input"),
                        dec(t, "output"),
                        dec(t, "cacheWrite"),
                        dec(t, "cacheRead")));
            }
        }
        PriceRule.MultimodalPricing mm = PriceRule.MultimodalPricing.none();
        JsonNode mmNode = node.get("multimodal");
        if (mmNode != null && mmNode.isObject()) {
            mm = new PriceRule.MultimodalPricing(
                    nullableDec(mmNode, "imagePerUnit"),
                    nullableDec(mmNode, "audioPerSecond"),
                    nullableDec(mmNode, "videoPerSecond"));
        }
        return new PriceRule(model, tiers,
                dec(node, "channelMultiplier"),
                dec(node, "groupDiscount"),
                mm);
    }

    private static BigDecimal dec(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? BigDecimal.ZERO : new BigDecimal(v.asText());
    }

    /** 与 dec 的区别：缺省返回 null 而不是 0（多模态未配置项应视为"不按此计费"）。 */
    private static BigDecimal nullableDec(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : new BigDecimal(v.asText());
    }

    /**
     * 查询某虚拟模型的定价。未配置返回 null。
     */
    public PriceRule ruleFor(String virtualModel) {
        return rules.get(virtualModel);
    }

    public Map<String, PriceRule> allRules() {
        return rules;
    }
}

