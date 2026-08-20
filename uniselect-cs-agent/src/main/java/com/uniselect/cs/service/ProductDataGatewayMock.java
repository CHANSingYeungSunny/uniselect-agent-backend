package com.uniselect.cs.service;

import com.uniselect.cs.common.dto.IntentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ProductDataGateway 的 Mock 实现。
 *
 * <p>内存模拟商品系统：
 * <ul>
 *   <li>按 merchant_id 隔离的库存/价格/订单/物流样本数据；</li>
 *   <li>随机模拟 50~400ms 网络延迟（覆盖 P95 预算场景）；</li>
 *   <li>对小概率（约 8%）请求模拟上游失败，用于验证降级链路；</li>
 *   <li>非法/未知 merchant_id 直接拒绝（隔离底线）。</li>
 * </ul>
 * </p>
 */
@Service
@Profile("mock")
public class ProductDataGatewayMock implements ProductDataGateway {

    private static final Logger log = LoggerFactory.getLogger(ProductDataGatewayMock.class);
    private final Random random = ThreadLocalRandom.current();

    /** merchant_id -> (sku -> stock) 等样本；此处简化用单 SKU 演示 */
    private final Map<String, Integer> stockSamples = new ConcurrentHashMap<>();
    private final Map<String, String> priceSamples = new ConcurrentHashMap<>();

    public ProductDataGatewayMock() {
        stockSamples.put("M-1001", 158);
        priceSamples.put("M-1001", "¥99.00");
        stockSamples.put("M-1002", 0);
        priceSamples.put("M-1002", "¥129.00");
    }

    @Override
    public String query(String merchantId, IntentType intent, String query) {
        if (merchantId == null || !merchantId.startsWith("M-")) {
            throw new IllegalArgumentException("MERCHANT_ISOLATION: invalid merchant_id");
        }
        // 模拟网络延迟
        int delay = 50 + random.nextInt(350);
        sleepQuietly(delay);

        // 模拟上游失败（约 8%）
        if (random.nextInt(100) < 8) {
            throw new RuntimeException("UPSTREAM_UNAVAILABLE: product system timeout");
        }

        return switch (intent) {
            case STOCK -> {
                Integer s = stockSamples.get(merchantId);
                yield s == null ? "无库存数据" : (s > 0 ? "现货 " + s + " 件" : "已售罄");
            }
            case PRICE -> {
                String p = priceSamples.get(merchantId);
                yield p == null ? "无价格数据" : p;
            }
            case ORDER -> "订单 " + (query == null ? "***" : query) + " 已发货，运输中";
            case LOGISTICS -> "物流：包裹已到达【本市分拨中心】，预计今日送达";
            default -> throw new IllegalArgumentException("intent not dynamic: " + intent);
        };
    }

    private void sleepQuietly(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
