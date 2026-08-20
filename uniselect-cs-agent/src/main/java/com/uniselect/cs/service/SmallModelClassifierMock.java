package com.uniselect.cs.service;

import com.uniselect.cs.common.dto.IntentType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 小模型分类器 Mock 实现。
 * 模拟一个轻量分类模型（约 120ms 推理），对规则未覆盖的输入做兜底分类。
 * 真实实现替换为豆包 lite / DeepSeek 小模型调用，接口不变。
 */
@Service
@Profile("mock")
public class SmallModelClassifierMock implements IntentRecognitionServiceImpl.SmallModelClassifier {

    @Override
    public Classification classify(String merchantId, String message) {
        // 模拟小模型推理延迟 ~120ms
        try {
            Thread.sleep(TimeUnit.MILLISECONDS.toMillis(120));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Mock 兜底逻辑：含"买/要/想"视为售前未知，其它归 UNKNOWN
        if (message.matches(".*(怎么|如何|什么|哪).*")) {
            return new Classification(IntentType.UNKNOWN, 0.6);
        }
        return new Classification(IntentType.UNKNOWN, 0.5);
    }
}
