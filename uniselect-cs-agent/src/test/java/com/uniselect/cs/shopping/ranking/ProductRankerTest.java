package com.uniselect.cs.shopping.ranking;

import com.uniselect.cs.shopping.model.ProductCandidate;
import com.uniselect.cs.shopping.model.RankedProduct;
import com.uniselect.cs.shopping.model.UserContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四维加权排序单测（直接 new Mock 实现）。
 *
 * <p>重点验证：① 综合分降序；② 多样性控制（同子品类 ≤ 半数）；③ 库存紧张度分段生效；
 * ④ 空输入返回空。</p>
 */
class ProductRankerTest {

    private final ProductRankerImpl ranker = new ProductRankerImpl(5, 0.4, 0.2, 0.2, 0.2, 0.5, 100);

    private ProductCandidate cand(String sku, String cat, double price, int inv, boolean promo, double disc, double score) {
        return new ProductCandidate(sku, "M-1001", "n-" + sku, cat, price, price * 0.5, inv, 1, promo, disc, score);
    }

    @Test
    void 综合分降序且返回TopN() {
        List<ProductCandidate> in = List.of(
                cand("A", "杯具", 100, 10, false, 0, 0.9),
                cand("B", "杯具", 50, 30, true, 10, 0.6),
                cand("C", "个护", 200, 5, false, 0, 0.4),
                cand("D", "小家电", 300, 60, true, 50, 0.3),
                cand("E", "杯具", 80, 8, false, 0, 0.7),
                cand("F", "杯具", 120, 12, false, 0, 0.5),
                cand("G", "食品", 90, 20, false, 0, 0.2)
        );
        List<RankedProduct> out = ranker.rank(in, new UserContext("M-1001", "s1", 0, List.of()));
        assertEquals(5, out.size(), "应返回 Top-N=5");
        for (int i = 1; i < out.size(); i++) {
            assertTrue(out.get(i - 1).rankScore() >= out.get(i).rankScore(), "应综合分降序");
        }
    }

    @Test
    void 多样性控制_同子品类不超过半数() {
        // 7 个均为"杯具"，Top-N=5，按半数限制最多 2 个杯具，其余被其它子品类（若有）替代
        // 此处全部同子品类，故最多取 floor(0.5*5)=2
        List<ProductCandidate> in = List.of(
                cand("A", "杯具", 100, 10, false, 0, 0.9),
                cand("B", "杯具", 50, 30, true, 10, 0.6),
                cand("C", "杯具", 200, 5, false, 0, 0.4),
                cand("D", "杯具", 300, 60, true, 50, 0.3),
                cand("E", "杯具", 80, 8, false, 0, 0.7),
                cand("F", "杯具", 120, 12, false, 0, 0.5),
                cand("G", "杯具", 90, 20, false, 0, 0.2)
        );
        List<RankedProduct> out = ranker.rank(in, new UserContext("M-1001", "s1", 0, List.of()));
        long cupCount = out.stream().filter(p -> "杯具".equals(p.subCategory())).count();
        assertTrue(cupCount <= 2, "同子品类(杯具)应 ≤ 半数(2)，实际=" + cupCount);
    }

    @Test
    void 库存紧张度分段_紧张者靠前() {
        List<ProductCandidate> in = List.of(
                cand("A", "杯具", 100, 100, false, 0, 0.3),  // 库存充足
                cand("B", "杯具", 100, 3, false, 0, 0.3)     // 库存紧张
        );
        List<RankedProduct> out = ranker.rank(in, new UserContext("M-1001", "s1", 0, List.of()));
        // 二者召回分相同，库存紧张(B)应因库存维权重更高而靠前
        assertEquals("B", out.get(0).skuId(), "库存紧张商品应优先");
    }

    @Test
    void 空输入返回空() {
        List<RankedProduct> out = ranker.rank(List.of(), new UserContext("M-1001", "s1", 0, List.of()));
        assertTrue(out.isEmpty(), "空候选应返回空");
    }
}
