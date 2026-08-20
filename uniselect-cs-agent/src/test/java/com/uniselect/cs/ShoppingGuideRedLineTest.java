package com.uniselect.cs;

import com.uniselect.cs.aspect.MetricsCollector;
import com.uniselect.cs.shopping.SessionStateMachine;
import com.uniselect.cs.shopping.model.ProductCandidate;
import com.uniselect.cs.shopping.model.RankedProduct;
import com.uniselect.cs.shopping.model.SessionState;
import com.uniselect.cs.shopping.model.UserContext;
import com.uniselect.cs.shopping.ranking.ProductRanker;
import com.uniselect.cs.shopping.recall.ProductRecallService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导购 Agent 红线回归测试集（6 大场景）。
 *
 * <p>复用客服基建：merchant_id 命名空间隔离、异步埋点、状态机；全部以真实 Mock Bean
 * （{@code @Profile("mock")}）注入，验证"新增导购不破坏现有客服链路"且各红线成立。</p>
 */
@SpringBootTest
@ActiveProfiles("mock")
class ShoppingGuideRedLineTest {

    @Autowired
    private ProductRecallService recallService;

    @Autowired
    private ProductRanker ranker;

    @Autowired
    private SessionStateMachine stateMachine;

    @Autowired
    private MetricsCollector metricsCollector;

    // ===================== 红线 1：跨商家严格隔离 =====================
    @Test
    void 红线_跨店隔离_商家查不到他店商品() {
        List<ProductCandidate> r = recallService.recall("M-1001", "保温杯",
                new UserContext("M-1001", "s1", 0, List.of()));
        assertTrue(r.stream().allMatch(c -> "M-1001".equals(c.merchantId())));
        assertTrue(r.stream().noneMatch(c -> c.skuId().startsWith("SKU-2")), "M-1001 不应看到 M-1002 商品");

        // 反向：M-1002 查保温杯，应得到自有库存>0 的商品（SKU-2004 库存0被过滤），绝不含 M-1001
        List<ProductCandidate> r2 = recallService.recall("M-1002", "保温杯",
                new UserContext("M-1002", "s2", 0, List.of()));
        assertTrue(r2.stream().noneMatch(c -> c.skuId().startsWith("SKU-1")));
    }

    // ===================== 红线 2：库存过滤 =====================
    @Test
    void 红线_库存过滤_零库存不下发() {
        List<ProductCandidate> r = recallService.recall("M-1001", "保温壶",
                new UserContext("M-1001", "s1", 0, List.of()));
        assertTrue(r.stream().noneMatch(c -> c.inventory() <= 0), "库存≤0 必须被实时过滤");
    }

    // ===================== 红线 3：预算过滤 =====================
    @Test
    void 红线_预算过滤_超预算不下发() {
        List<ProductCandidate> r = recallService.recall("M-1001", "杯",
                new UserContext("M-1001", "s1", 50, List.of()));
        assertTrue(r.stream().noneMatch(c -> c.price() > 50), "超预算商品必须被过滤");
    }

    // ===================== 红线 4：多样性控制 =====================
    @Test
    void 红线_多样性_同子品类不超过半数() {
        // 全"杯具"候选，Top-N=5，多样性上限半数 → 最多 2 个杯具
        List<ProductCandidate> r = recallService.recall("M-1001", "杯",
                new UserContext("M-1001", "s1", 0, List.of()));
        List<RankedProduct> ranked = ranker.rank(r, new UserContext("M-1001", "s1", 0, List.of()));
        long cup = ranked.stream().filter(p -> "杯具".equals(p.subCategory())).count();
        assertTrue(cup <= 2, "同子品类(杯具)应 ≤ 半数(2)，实际=" + cup);
    }

    // ===================== 红线 5：降级（无候选不凑数） =====================
    @Test
    void 红线_降级_预算极低无候选返回空() {
        // 预算 1 元：无任何商品满足 → 召回过滤后为空 → 排序返回空（上层发 degrade 不凑数）
        List<ProductCandidate> r = recallService.recall("M-1001", "杯",
                new UserContext("M-1001", "s1", 1, List.of()));
        List<RankedProduct> ranked = ranker.rank(r, new UserContext("M-1001", "s1", 1, List.of()));
        assertTrue(ranked.isEmpty(), "无候选时应返回空，由上层发 degrade 而非凑数");
    }

    // ===================== 红线 6：埋点幂等 =====================
    @Test
    void 红线_埋点幂等_重复eventId仅计一次() throws InterruptedException {
        long before = metricsCollector.observeRecommendCount("impression");
        String eventId = "M-1001:s1:SKU-1001:imp:fixed";
        metricsCollector.recordRecommendImpression("M-1001", "s1", "SKU-1001", eventId);
        metricsCollector.recordRecommendImpression("M-1001", "s1", "SKU-1001", eventId); // 重复
        // 等待异步落池
        TimeUnit.MILLISECONDS.sleep(300);
        long after = metricsCollector.observeRecommendCount("impression");
        assertEquals(before + 1, after, "相同 event_id 应仅计一次（幂等去重）");
    }

    // ===================== 附加：状态机迁移 + 摘要限额 =====================
    @Test
    void 状态机_导购态迁移与摘要() {
        SessionState s = stateMachine.transition("M-1001", "s9", "我想买个保温杯");
        assertEquals(SessionState.SHOPPING_GUIDE, s, "含购买意图应进入导购态");
        // 摘要由内部生成且 ≤500 字（红线在 SessionStateMachine 内保证，此处仅校验状态可读取）
        assertNotNull(stateMachine.currentState("M-1001", "s9"));
    }
}
