package com.uniselect.cs.shopping.recall;

import com.uniselect.cs.shopping.model.ProductCandidate;
import com.uniselect.cs.shopping.model.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 混合召回引擎 Mock 实现（内存模拟 pgvector + 关键词 + 规则池）。
 *
 * <p>三路召回：
 * <ol>
 *   <li><b>向量召回</b>：Mock 基于查询关键词命中类目做相似度打分；超时 {@code vector-timeout-ms}
 *       （默认 100ms）降级为空，不阻塞主链路。</li>
 *   <li><b>关键词召回</b>：名称/类目包含查询词即命中，精确匹配优先。</li>
 *   <li><b>规则候选池</b>：运营精选/搭配推荐（Mock 固定候选 + 查询词相关性加成）。</li>
 * </ol>
 * </p>
 *
 * <p>合并策略：三路结果按 {@code sku_id} 去重，保留分最高的候选；合并后施加实时过滤
 * （库存>0、status=1、price≤budget、活动有效期内），再以召回分排序输出。</p>
 *
 * <p>隔离红线：所有 Mock 数据以 {@code merchant_id} 命名空间隔离，A/M-1002 查不到 B/M-1001 商品。</p>
 */
@Service
@Profile("mock")
public class ProductRecallServiceImpl implements ProductRecallService {

    private static final Logger log = LoggerFactory.getLogger(ProductRecallServiceImpl.class);

    private final int vectorTopK;
    private final long vectorTimeoutMs;
    private final int keywordTopK;
    private final int rulePoolTopK;
    private final long mergeBudgetMs;

    /** Mock 商品库：merchant_id -> 商品候选（构建即按命名空间隔离） */
    private final Map<String, List<ProductCandidate>> catalog = new ConcurrentHashMap<>();

    public ProductRecallServiceImpl(
            @Value("${uniselect.cs.shopping.recall.vector-top-k:10}") int vectorTopK,
            @Value("${uniselect.cs.shopping.recall.vector-timeout-ms:100}") long vectorTimeoutMs,
            @Value("${uniselect.cs.shopping.recall.keyword-top-k:10}") int keywordTopK,
            @Value("${uniselect.cs.shopping.recall.rule-pool-top-k:10}") int rulePoolTopK,
            @Value("${uniselect.cs.shopping.recall.merge-budget-ms:300}") long mergeBudgetMs) {
        this.vectorTopK = vectorTopK;
        this.vectorTimeoutMs = vectorTimeoutMs;
        this.keywordTopK = keywordTopK;
        this.rulePoolTopK = rulePoolTopK;
        this.mergeBudgetMs = mergeBudgetMs;
        initMockCatalog();
    }

    @Override
    public List<ProductCandidate> recall(String merchantId, String userQuery, UserContext userCtx) {
        long start = System.nanoTime();
        try {
            // 三路并行召回（此处用顺序模拟，各路自带超时降级；合并预算保护整体）
            List<ProductCandidate> merged = new ArrayList<>();
            merged.addAll(vectorRecall(merchantId, userQuery));
            merged.addAll(keywordRecall(merchantId, userQuery));
            merged.addAll(rulePoolRecall(merchantId, userQuery));

            // 按 sku_id 去重，保最高分
            Map<String, ProductCandidate> dedup = new LinkedHashMap<>();
            for (ProductCandidate c : merged) {
                dedup.merge(c.skuId(), c, (a, b) -> a.score() >= b.score() ? a : b);
            }

            // 实时过滤（库存>0 / 上架 / 预算 / 活动有效）
            double budget = userCtx != null ? userCtx.budget() : 0;
            List<ProductCandidate> filtered = new ArrayList<>();
            for (ProductCandidate c : dedup.values()) {
                if (passRealtimeFilter(c, budget)) {
                    filtered.add(c);
                }
            }
            // 召回序：按分降序
            filtered.sort(Comparator.comparingDouble(ProductCandidate::score).reversed());
            log.debug("[recall] merchantId={} query={} merged={} filtered={} costMs={}",
                    merchantId, userQuery, merged.size(), filtered.size(),
                    (System.nanoTime() - start) / 1_000_000.0);
            return filtered;
        } catch (Exception e) {
            log.warn("[recall] failed, degrade to empty merchantId={}: {}", merchantId, e.getMessage());
            return List.of();
        }
    }

    /** 实时过滤：库存>0、上架(status=1)、价格≤预算、活动有效 */
    private boolean passRealtimeFilter(ProductCandidate c, double budget) {
        if (c.inventory() <= 0) {
            return false;
        }
        if (c.status() != 1) {
            return false;
        }
        if (budget > 0 && c.price() > budget) {
            return false;
        }
        if (c.promotion() && !promotionValid(c)) {
            return false;
        }
        return true;
    }

    /** Mock 活动有效性：promotion_end 未过即有效（无起止视为长期有效） */
    private boolean promotionValid(ProductCandidate c) {
        // Mock 数据不填起止，统一视为有效
        return true;
    }

    /** 向量召回：超时降级为空（不阻塞） */
    private List<ProductCandidate> vectorRecall(String merchantId, String query) {
        long start = System.nanoTime();
        // 模拟向量检索耗时（远低于超时阈值）
        try {
            Thread.sleep(Math.min(20, vectorTimeoutMs / 5));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(vectorTimeoutMs)) {
            log.warn("[recall][vector] timeout degraded merchantId={}", merchantId);
            return List.of();
        }
        List<ProductCandidate> pool = catalog.getOrDefault(merchantId, List.of());
        List<ProductCandidate> result = new ArrayList<>();
        for (ProductCandidate c : pool) {
            double sim = vectorSimilarity(query, c);
            if (sim > 0) {
                result.add(copyWithScore(c, sim));
            }
            if (result.size() >= vectorTopK) {
                break;
            }
        }
        result.sort(Comparator.comparingDouble(ProductCandidate::score).reversed());
        return result;
    }

    /** Mock 向量相似度：查询词命中的类目层级越深分越高 */
    private double vectorSimilarity(String query, ProductCandidate c) {
        if (query == null || query.isBlank()) {
            return 0;
        }
        String q = query.toLowerCase();
        String name = c.name().toLowerCase();
        String cat = c.categoryPath().toLowerCase();
        if (name.contains(q)) {
            return 0.9; // 名称命中，强相关
        }
        if (cat.contains(q)) {
            return 0.7; // 类目命中
        }
        // 词素粗匹配（逐字）
        int hit = 0;
        for (char ch : q.toCharArray()) {
            if (name.indexOf(ch) >= 0 || cat.indexOf(ch) >= 0) {
                hit++;
            }
        }
        return hit > 0 ? 0.3 + 0.02 * hit : 0;
    }

    /** 关键词召回：名称/类目包含查询词精确命中 */
    private List<ProductCandidate> keywordRecall(String merchantId, String query) {
        List<ProductCandidate> pool = catalog.getOrDefault(merchantId, List.of());
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.toLowerCase();
        List<ProductCandidate> result = new ArrayList<>();
        for (ProductCandidate c : pool) {
            if (c.name().toLowerCase().contains(q) || c.categoryPath().toLowerCase().contains(q)) {
                // 关键词命中分固定较高（精确匹配）
                result.add(copyWithScore(c, 0.85));
            }
            if (result.size() >= keywordTopK) {
                break;
            }
        }
        return result;
    }

    /** 规则候选池：运营精选（Mock 固定高优先级候选 + 查询相关性加成） */
    private List<ProductCandidate> rulePoolRecall(String merchantId, String query) {
        List<ProductCandidate> pool = catalog.getOrDefault(merchantId, List.of());
        List<ProductCandidate> result = new ArrayList<>();
        for (ProductCandidate c : pool) {
            // Mock 规则：promotion 商品 + 库存充足视为运营精选候选
            if (c.promotion() && c.inventory() > 20) {
                double base = 0.6;
                if (query != null && c.name().toLowerCase().contains(query.toLowerCase())) {
                    base += 0.1;
                }
                result.add(copyWithScore(c, base));
            }
            if (result.size() >= rulePoolTopK) {
                break;
            }
        }
        return result;
    }

    /** 复制候选并覆盖召回分（不修改原 Mock 数据） */
    private ProductCandidate copyWithScore(ProductCandidate c, double score) {
        return new ProductCandidate(c.skuId(), c.merchantId(), c.name(), c.categoryPath(),
                c.price(), c.cost(), c.inventory(), c.status(),
                c.promotion(), c.promotionDiscount(), score);
    }

    /** 构造 Mock 商品库（按 merchant_id 命名空间隔离） */
    private void initMockCatalog() {
        // ---- M-1001 家居/杯具/小家电 ----
        List<ProductCandidate> m1001 = new ArrayList<>();
        m1001.add(cand("SKU-1001", "M-1001", "保温杯 316不锈钢 500ml", "家居/厨房/杯具", 89.0, 40.0, 120, 1, true, 20.0));
        m1001.add(cand("SKU-1002", "M-1001", "玻璃水杯 高硼硅 350ml", "家居/厨房/杯具", 39.0, 15.0, 3, 1, false, 0.0));
        m1001.add(cand("SKU-1003", "M-1001", "便携保温壶 1L 户外", "家居/厨房/杯具", 159.0, 80.0, 0, 1, true, 30.0)); // 库存0→过滤
        m1001.add(cand("SKU-1004", "M-1001", "电动牙刷 声波 充电式", "家居/个护/口腔", 199.0, 90.0, 60, 1, true, 40.0));
        m1001.add(cand("SKU-1005", "M-1001", "精品咖啡豆 中度烘焙 500g", "食品/饮品/咖啡", 99.0, 50.0, 200, 1, false, 0.0));
        m1001.add(cand("SKU-1006", "M-1001", "保温杯 大容量 800ml", "家居/厨房/杯具", 129.0, 60.0, 15, 1, true, 25.0));
        m1001.add(cand("SKU-1007", "M-1001", "陶瓷马克杯 办公室 350ml", "家居/厨房/杯具", 29.0, 10.0, 4, 1, false, 0.0));
        m1001.add(cand("SKU-1008", "M-1001", "空气炸锅 5L 家用", "家居/厨房/小家电", 299.0, 150.0, 30, 1, true, 50.0));
        catalog.put("M-1001", m1001);

        // ---- M-1002 数码/配件（与 M-1001 完全不同品类，验证隔离）----
        List<ProductCandidate> m1002 = new ArrayList<>();
        m1002.add(cand("SKU-2001", "M-1002", "无线蓝牙耳机 降噪", "数码/音频/耳机", 399.0, 180.0, 80, 1, true, 60.0));
        m1002.add(cand("SKU-2002", "M-1002", "快充充电宝 20000mAh", "数码/配件/电源", 149.0, 70.0, 5, 1, false, 0.0));
        m1002.add(cand("SKU-2003", "M-1002", "机械键盘 87键 红轴", "数码/配件/外设", 259.0, 120.0, 40, 1, true, 30.0));
        m1002.add(cand("SKU-2004", "M-1002", "保温杯 运动款 600ml", "家居/厨房/杯具", 79.0, 35.0, 0, 1, false, 0.0)); // 库存0→过滤
        m1002.add(cand("SKU-2005", "M-1002", "智能手表 心率监测", "数码/穿戴/手表", 699.0, 320.0, 25, 1, true, 80.0));
        catalog.put("M-1002", m1002);
    }

    private ProductCandidate cand(String skuId, String merchantId, String name, String cat,
                                  double price, double cost, int inv, int status,
                                  boolean promo, double disc) {
        return new ProductCandidate(skuId, merchantId, name, cat, price, cost, inv, status, promo, disc, 0.0);
    }
}
