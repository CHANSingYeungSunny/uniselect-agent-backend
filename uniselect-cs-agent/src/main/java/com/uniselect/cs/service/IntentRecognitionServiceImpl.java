package com.uniselect.cs.service;

import com.uniselect.cs.common.dto.IntentResult;
import com.uniselect.cs.common.dto.IntentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 意图识别实现（规则直判 + 小模型兜底）。
 *
 * <p>性能红线：意图识别 < 200ms。规则直判亚毫秒；小模型兜底（Mock）模拟 ~120ms，
 * 仅规则未覆盖时触发。</p>
 */
@Service
public class IntentRecognitionServiceImpl implements IntentRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(IntentRecognitionServiceImpl.class);

    /** 高频意图关键词规则表（精确/子串匹配，O(1) 命中即返回，不调模型） */
    private static final Map<IntentType, List<String>> RULE_KEYWORDS = Map.of(
            IntentType.STOCK, List.of("库存", "有货", "现货", "还有吗", "补货", "断货", "售罄"),
            IntentType.PRICE, List.of("多少钱", "价格", "售价", "价位", "标价", "优惠价", "怎么卖"),
            IntentType.ORDER, List.of("订单", "我的单", "下单", "付款", "待发货"),
            IntentType.LOGISTICS, List.of("物流", "快递", "发货", "派送", "到哪了", "签收"),
            IntentType.SPEC, List.of("规格", "材质", "参数", "尺寸", "容量", "颜色"),
            IntentType.POLICY, List.of("退货", "退款政策", "退换", "售后", "保修"),
            IntentType.SHIPPING, List.of("运费", "邮费", "包邮", "偏远"),
            IntentType.ACTIVITY, List.of("活动", "优惠券", "满减", "折扣", "促销"),
            IntentType.GREETING, List.of("你好", "您好", "在吗", "hi", "hello")
    );

    private final SmallModelClassifier smallModelClassifier;

    public IntentRecognitionServiceImpl(SmallModelClassifier smallModelClassifier) {
        this.smallModelClassifier = smallModelClassifier;
    }

    @Override
    public IntentResult recognize(String merchantId, String message) {
        if (message == null || message.isBlank()) {
            return IntentResult.rule(IntentType.UNKNOWN, 0L);
        }
        long start = System.nanoTime();

        // 1) 规则直判（优先，O(1)）
        for (Map.Entry<IntentType, List<String>> e : RULE_KEYWORDS.entrySet()) {
            for (String kw : e.getValue()) {
                if (message.contains(kw)) {
                    return IntentResult.rule(e.getKey(), elapsed(start));
                }
            }
        }

        // 2) 规则未覆盖 → 小模型兜底分类（Mock）
        SmallModelClassifier.Classification cls = smallModelClassifier.classify(merchantId, message);
        log.debug("[intent] rule miss, fallback small-model -> {} conf={}",
                cls.intent(), cls.confidence());
        return IntentResult.smallModel(cls.intent(), cls.confidence(), elapsed(start));
    }

    private long elapsed(long startNanos) {
        return System.nanoTime() - startNanos;
    }

    /** 小模型分类器（Mock，评审建议：意图分类用轻量级小模型，成本约主模型 1/10） */
    public interface SmallModelClassifier {
        record Classification(IntentType intent, double confidence) {}

        Classification classify(String merchantId, String message);
    }
}
