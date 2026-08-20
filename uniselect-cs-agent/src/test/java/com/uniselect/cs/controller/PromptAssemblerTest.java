package com.uniselect.cs.controller;

import com.uniselect.cs.common.dto.ChatTurn;
import com.uniselect.cs.common.dto.RagRetrieveResult;
import com.uniselect.cs.common.dto.ToolUseResult;
import com.uniselect.cs.service.MerchantConfigServiceMock;
import com.uniselect.cs.service.PromptAssembler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 红线回归测试集 —— Prompt 组装顺序（Step 3/5 修复的 Prompt 位置契约锁定点）。
 *
 * <p>红线：历史上下文必须位于<b>系统规则层之后</b>、<b>商家业务层之前</b>，
 * 确保系统规则层永远最先且不可被历史对话/商家配置覆盖。</p>
 */
class PromptAssemblerTest {

    private PromptAssembler newAssembler() {
        // MerchantConfigServiceMock 为 M-1001 预设了商家业务层 Prompt，便于断言业务层存在
        return new PromptAssembler(new MerchantConfigServiceMock());
    }

    @Test
    void 历史上下文位于系统规则层之后_商家业务层之前() {
        PromptAssembler assembler = newAssembler();
        List<ChatTurn> history = List.of(
                new ChatTurn("上一轮用户问了库存", "上一轮客服回复了现货", System.currentTimeMillis()));
        String prompt = assembler.assemble(
                "M-1001", "现在还有货吗", history, RagRetrieveResult.empty(), List.of());

        int idxSystemRule = prompt.indexOf("不可违背的系统规则");
        int idxHistory = prompt.indexOf("[历史对话上下文]");
        int idxBusiness = prompt.indexOf("[商家业务层]");
        int idxUserInput = prompt.indexOf("以下为消费者用户输入");

        // 所有锚点必须存在
        assertTrue(idxSystemRule >= 0, "系统规则层缺失");
        assertTrue(idxHistory >= 0, "历史上下文区块缺失");
        assertTrue(idxBusiness >= 0, "商家业务层区块缺失");
        assertTrue(idxUserInput >= 0, "用户输入区块缺失");

        // 严格顺序：系统规则层 < 历史上下文 < 商家业务层 < 用户输入
        assertTrue(idxSystemRule < idxHistory,
                "历史上下文必须在系统规则层之后");
        assertTrue(idxHistory < idxBusiness,
                "历史上下文必须在商家业务层之前（红线：不得置于系统规则层之前）");
        assertTrue(idxBusiness < idxUserInput,
                "商家业务层必须在用户输入之前");
    }

    @Test
    void 无历史时不含历史上下文区块_顺序仍正确() {
        PromptAssembler assembler = newAssembler();
        String prompt = assembler.assemble(
                "M-1001", "你好", List.of(), RagRetrieveResult.empty(), List.of());

        assertTrue(prompt.indexOf("不可违背的系统规则") >= 0);
        assertTrue(prompt.indexOf("[历史对话上下文]") < 0,
                "无历史时不应出现历史上下文区块");
        assertTrue(prompt.indexOf("[商家业务层]") >= 0);
    }
}
