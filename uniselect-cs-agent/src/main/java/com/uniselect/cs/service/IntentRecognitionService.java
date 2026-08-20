package com.uniselect.cs.service;

import com.uniselect.cs.common.dto.IntentResult;
import com.uniselect.cs.common.dto.IntentType;

/**
 * 意图识别服务（评审建议 3.1：规则 + 小模型双通道）。
 *
 * <p>高频意图（问价格/库存/发货/订单/物流/退换货）先用<b>关键词规则直判 O(1)</b>，
 * 规则未覆盖再调<b>小模型兜底分类</b>（此处 Mock 小模型），只有生成回复时才用大模型。
 * 实测客服 70%+ 为高频标准问法，双通道显著降本降延迟。</p>
 */
public interface IntentRecognitionService {

    /**
     * 识别意图。主链路只走规则；规则未命中才降级到小模型。
     *
     * @param merchantId 商家 ID（小模型兜底时可结合商家业务层，Step 3 接入）
     * @param message    消费者输入
     */
    IntentResult recognize(String merchantId, String message);
}
