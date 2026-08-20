package com.uniselect.cs.shopping.recall;

import com.uniselect.cs.shopping.model.ProductCandidate;
import com.uniselect.cs.shopping.model.UserContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 混合召回引擎单测（直接 new Mock 实现，对齐现有测试风格）。
 *
 * <p>重点验证隔离红线与实时过滤：
 * ① A 商家查不到 B 商家商品；② 库存=0 / 下架被过滤；③ 超预算被过滤；④ sku 去重保高分。</p>
 */
class ProductRecallServiceTest {

    private final ProductRecallServiceImpl recall = new ProductRecallServiceImpl(10, 100, 10, 10, 300);

    @Test
    void 跨商家隔离_M1001查不到M1002商品() {
        List<ProductCandidate> r = recall.recall("M-1001", "保温杯",
                new UserContext("M-1001", "s1", 0, List.of()));
        assertTrue(r.stream().allMatch(c -> "M-1001".equals(c.merchantId())), "召回结果应全部属于 M-1001");
        assertTrue(r.stream().anyMatch(c -> c.skuId().equals("SKU-1001")), "应召回 M-1001 的保温杯");
        assertTrue(r.stream().noneMatch(c -> c.skuId().startsWith("SKU-2")), "不应出现 M-1002 商品");
    }

    @Test
    void 库存为零被过滤() {
        // SKU-1003 库存=0，应在结果中消失
        List<ProductCandidate> r = recall.recall("M-1001", "保温壶",
                new UserContext("M-1001", "s1", 0, List.of()));
        assertTrue(r.stream().noneMatch(c -> c.skuId().equals("SKU-1003")), "库存=0 商品必须被过滤");
    }

    @Test
    void 超出预算被过滤() {
        // 预算 50：SKU-1001(89) 应被过滤，SKU-1007(29) 可选入
        List<ProductCandidate> r = recall.recall("M-1001", "杯",
                new UserContext("M-1001", "s1", 50, List.of()));
        assertTrue(r.stream().noneMatch(c -> c.price() > 50), "超预算商品必须被过滤");
        assertTrue(r.stream().anyMatch(c -> c.skuId().equals("SKU-1007")), "29 元马克杯应在预算内");
    }

    @Test
    void sku去重保高分_三路合并不重复() {
        List<ProductCandidate> r = recall.recall("M-1001", "保温杯",
                new UserContext("M-1001", "s1", 0, List.of()));
        long unique = r.stream().map(ProductCandidate::skuId).distinct().count();
        assertEquals(r.size(), unique, "结果中不应有重复 sku_id");
    }

    @Test
    void 空查询返回非空召回_基于类目相关性() {
        List<ProductCandidate> r = recall.recall("M-1001", "",
                new UserContext("M-1001", "s1", 0, List.of()));
        assertFalse(r.isEmpty(), "空查询也应基于规则池/类目召回部分候选");
    }
}
