package com.uniselect.cs.shopping.ranking;

import com.uniselect.cs.shopping.model.ProductCandidate;
import com.uniselect.cs.shopping.model.RankedProduct;
import com.uniselect.cs.shopping.model.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 四维加权排序 Mock 实现。
 *
 * <p>四维（先各自归一化到 0~1，再加权）：
 * <ol>
 *   <li><b>相关性 relevance</b>（权重 0.4）：取召回分归一化。</li>
 *   <li><b>价格优势 price</b>（权重 0.2）：(1 - price/maxPrice) 越便宜越高；预算内为正向。</li>
 *   <li><b>活动力度 promotion</b>（权重 0.2）：优惠占原价比例，越高越好（无活动为 0）。</li>
 *   <li><b>库存紧张度 inventory</b>（权重 0.2）：分段（≤5→0.9, 6~20→0.5, &gt;20→0.2），
 *       紧张反而优先（稀缺推荐），但库存&gt;0 已由召回过滤保证。</li>
 * </ol>
 * </p>
 *
 * <p><b>多样性控制</b>：最终 Top-N 中，同一子品类数量 ≤ {@code maxSameSubcategoryRatio * topN}
 * （默认 ≤ 半数）。实现上按综合分降序贪心选入，超阈值子品类跳过直到额度释放。</p>
 */
@Service
@Profile("mock")
public class ProductRankerImpl implements ProductRanker {

    private static final Logger log = LoggerFactory.getLogger(ProductRankerImpl.class);

    private final int topN;
    private final double wRelevance;
    private final double wPrice;
    private final double wPromotion;
    private final double wInventory;
    private final double maxSameSubRatio;
    private final long budgetMs;

    public ProductRankerImpl(
            @Value("${uniselect.cs.shopping.ranking.top-n:5}") int topN,
            @Value("${uniselect.cs.shopping.ranking.weight-relevance:0.4}") double wRelevance,
            @Value("${uniselect.cs.shopping.ranking.weight-price:0.2}") double wPrice,
            @Value("${uniselect.cs.shopping.ranking.weight-promotion:0.2}") double wPromotion,
            @Value("${uniselect.cs.shopping.ranking.weight-inventory:0.2}") double wInventory,
            @Value("${uniselect.cs.shopping.ranking.max-same-subcategory-ratio:0.5}") double maxSameSubRatio,
            @Value("${uniselect.cs.shopping.ranking.budget-ms:100}") long budgetMs) {
        this.topN = topN;
        this.wRelevance = wRelevance;
        this.wPrice = wPrice;
        this.wPromotion = wPromotion;
        this.wInventory = wInventory;
        this.maxSameSubRatio = maxSameSubRatio;
        this.budgetMs = budgetMs;
    }

    @Override
    public List<RankedProduct> rank(List<ProductCandidate> candidates, UserContext userCtx) {
        long start = System.nanoTime();
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        try {
            // 1) 各维度分别归一化所需的极值
            double maxRecall = candidates.stream().mapToDouble(ProductCandidate::score).max().orElse(1.0);
            double minRecall = candidates.stream().mapToDouble(ProductCandidate::score).min().orElse(0.0);
            double maxPrice = candidates.stream().mapToDouble(ProductCandidate::price).max().orElse(1.0);

            // 2) 计算每个候选四维归一化分 + 综合分
            List<Scored> scored = new ArrayList<>();
            for (ProductCandidate c : candidates) {
                double rel = normalize(c.score(), minRecall, maxRecall);
                double price = 1.0 - safeDiv(c.price(), maxPrice); // 越便宜越高
                double promo = c.promotion() ? safeDiv(c.promotionDiscount(), c.price()) : 0.0;
                double inv = inventoryTension(c.inventory());
                double total = wRelevance * rel + wPrice * price + wPromotion * promo + wInventory * inv;
                scored.add(new Scored(c, total, rel, price, promo, inv));
            }

            // 3) 综合分降序
            scored.sort(Comparator.comparingDouble((Scored s) -> s.total).reversed());

            // 4) 多样性控制贪心选入
            int maxSameSub = (int) Math.floor(maxSameSubRatio * topN);
            Map<String, Integer> subCount = new HashMap<>();
            List<RankedProduct> result = new ArrayList<>();
            for (Scored s : scored) {
                if (result.size() >= topN) {
                    break;
                }
                String sub = s.candidate.subCategory();
                int cnt = subCount.getOrDefault(sub, 0);
                if (cnt >= maxSameSub) {
                    continue; // 该子品类已达上限，跳过（后续若有其它子品类腾出名额仍可选）
                }
                subCount.put(sub, cnt + 1);
                result.add(new RankedProduct(s.candidate, s.total, buildReason(s)));
            }

            log.debug("[rank] merchantId={} in={} out={} costMs={}",
                    userCtx != null ? userCtx.merchantId() : "?",
                    candidates.size(), result.size(),
                    (System.nanoTime() - start) / 1_000_000.0);
            return result;
        } catch (Exception e) {
            log.warn("[rank] failed, degrade to recall order: {}", e.getMessage());
            // 排序异常降级：直接取召回序前 topN
            List<RankedProduct> fallback = new ArrayList<>();
            for (int i = 0; i < Math.min(topN, candidates.size()); i++) {
                ProductCandidate c = candidates.get(i);
                fallback.add(new RankedProduct(c, c.score(), buildReason(new Scored(c, c.score(), 0, 0, 0, 0))));
            }
            return fallback;
        }
    }

    /** 库存紧张度分段：≤5→0.9（稀缺优先），6~20→0.5，>20→0.2 */
    private double inventoryTension(int inventory) {
        if (inventory <= 5) {
            return 0.9;
        }
        if (inventory <= 20) {
            return 0.5;
        }
        return 0.2;
    }

    /** 归一化到 0~1（min==max 时归中 0.5） */
    private double normalize(double v, double min, double max) {
        if (max - min < 1e-9) {
            return 0.5;
        }
        return (v - min) / (max - min);
    }

    private double safeDiv(double a, double b) {
        return b <= 0 ? 0 : a / b;
    }

    private String buildReason(Scored s) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.candidate.name());
        if (s.candidate.promotion()) {
            sb.append("，活动立减¥").append(s.candidate.promotionDiscount());
        }
        if (s.candidate.inventory() <= 5) {
            sb.append("，库存紧张建议尽快入手");
        } else if (s.candidate.inventory() <= 20) {
            sb.append("，热销中库存有限");
        }
        return sb.toString();
    }

    /** 内部带四维分的持有结构 */
    private static final class Scored {
        final ProductCandidate candidate;
        final double total;
        final double rel;
        final double price;
        final double promo;
        final double inv;

        Scored(ProductCandidate candidate, double total, double rel, double price, double promo, double inv) {
            this.candidate = candidate;
            this.total = total;
            this.rel = rel;
            this.price = price;
            this.promo = promo;
            this.inv = inv;
        }
    }
}
