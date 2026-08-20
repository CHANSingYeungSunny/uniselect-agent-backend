package com.uniselect.cs.common.constant;

import com.uniselect.cs.common.dto.IntentType;

/**
 * 降级话术常量（动态层字段级降级锚点，对齐《动态知识库搭建方案》5.2）。
 * 失败/超时一律返回"暂不可查，建议联系人工"，严禁返回静态旧值。
 */
public final class DegradeConstants {

    private DegradeConstants() {
    }

    public static String forIntent(IntentType intent) {
        return switch (intent) {
            case STOCK -> "库存暂不可查，建议联系人工";
            case PRICE -> "价格暂不可查，建议联系人工";
            case ORDER -> "订单状态暂不可查，建议联系人工";
            case LOGISTICS -> "物流暂不可查，建议联系人工";
            default -> "该项暂不可查，建议联系人工";
        };
    }
}
