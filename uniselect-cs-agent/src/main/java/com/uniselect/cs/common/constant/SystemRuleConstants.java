package com.uniselect.cs.common.constant;

import java.util.List;
import java.util.Set;

/**
 * 系统规则层（硬编码，服务端不可变）。
 *
 * <p>根据《评审与优化建议》2.1/2.3 与《客服 Agent 方案（代码实现版）》第八章：
 * 系统规则层是平台统一规则源的核心，任何商家业务层 DB 配置都不得覆盖、降级或绕过。
 * 本类为 Step 1 占位，仅承载「平台默认转人工触发词」与越权词占位，后续 Step 将补充
 * 系统规则层 Prompt 文本与三层拦截词表。</p>
 *
 * <p>注：平台默认转人工词是「不可被关闭」的硬约束（见红线回归测试集），商家仅可追加，
 * 不可删除默认词。</p>
 */
public final class SystemRuleConstants {

    private SystemRuleConstants() {
    }

    /**
     * 平台默认转人工触发词（精确匹配，确定性，完全不需要 LLM）。
     * 命中即短路转人工，不进 LLM 链路。
     */
    public static final Set<String> DEFAULT_HANDOFF_KEYWORDS = Set.of(
            "人工", "转人工", "人工客服", "退款", "退货", "投诉", "赔偿",
            "起诉", "举报", "维权", "协商还款", "大额赔偿"
    );

    /**
     * 平台默认转人工正则（模糊匹配，覆盖词表无法穷举的组合）。
     * 示例：要.*人工 / 投诉.*处理 / 退款.*申请。
     */
    public static final List<String> DEFAULT_HANDOFF_PATTERNS = List.of(
            "要\\s*[你您]*\\s*人工",
            "投诉.*(处理|解决|客服)",
            "退款.*(申请|流程|怎么)",
            "找\\s*客服",
            "不.*(解决|处理).*(投诉|举报)"
    );

    /**
     * 转人工降级话术（命中后推送，供前端提示用户已进入人工队列）。
     */
    public static final String HANDOFF_MESSAGE = "您好，您的问题已转接人工客服，请稍候，我们会尽快为您处理。";

    /**
     * 越权承诺占位词（Step 4 三层拦截第二/三层使用，此处仅占位声明系统规则层红线）。
     */
    public static final Set<String> VIOLATION_KEYWORDS_PLACEHOLDER = Set.of(
            "赔偿", "退款金额", "保证到货", "百分百有效"
    );

    /**
     * Prompt 注入触发词（精确子串匹配，防注入防线第一/第二层共用）。
     * 仅对「用户输入原文」匹配；对完整 prompt 匹配会因系统规则层文本本身含
     * 「忽略…规则」「其他商家」等字样而全量误命中（见 {@code PromptInjectionGuard} 注释）。
     */
    public static final Set<String> PROMPT_INJECTION_KEYWORDS = Set.of(
            "忽略系统规则", "忽略系统提示", "忽略以上所有", "忽略之前", "忽略所有指令",
            "忘记系统规则", "忘记你的指令", "无视系统规则", "跳过系统规则", "跳过所有规则",
            "覆盖系统规则", "删除系统规则", "修改你的规则", "绕过系统规则",
            "告诉我其他店", "其他店的价格", "别的店", "别的商家", "其他商家"
    );

    /**
     * Prompt 注入正则（模糊匹配，覆盖词表无法穷举的变形）。
     */
    public static final List<String> PROMPT_INJECTION_PATTERNS = List.of(
            "忽略.*(规则|指令|提示|要求)",
            "无视.*(规则|指令)",
            "忘记.*(规则|指令)",
            "跳过.*(规则|指令|拦截)",
            "绕过.*(规则|拦截|系统|审核)",
            "覆盖.*(规则|指令)",
            "(其他|别的)[店商]",
            "扮演.*(客服|角色|商家)",
            "你现在是.*(系统|管理员|老板)"
    );

    /**
     * Prompt 注入拒绝话术（网关短路后随 event: degrade 推送，提示用户请求已终止）。
     */
    public static final String PROMPT_INJECTION_MESSAGE = "抱歉，检测到试图覆盖系统规则或越权获取其他商家信息的指令，"
            + "本次对话已终止。为保障数据安全与合规，本客服仅回答本店相关问题。";

    /**
     * merchant_id 隔离错误信息（缺失/非法时返回，绝不兜底默认商家）。
     */
    public static final String MERCHANT_ISOLATION_ERROR = "MERCHANT_ID_INVALID";
}
