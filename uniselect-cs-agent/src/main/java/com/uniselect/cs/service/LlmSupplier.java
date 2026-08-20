package com.uniselect.cs.service;

/**
 * LLM 供应商标识（主备双供应商容灾，评审建议 3.5）。
 */
public enum LlmSupplier {
    PRIMARY("doubao"),     // 主：豆包（中文优、首 token 快、成本低）
    SECONDARY("deepseek"); // 备：DeepSeek（主超时/报错无感切换）

    private final String label;

    LlmSupplier(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
