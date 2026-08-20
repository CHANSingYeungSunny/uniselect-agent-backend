package com.uniselect.cs.interceptor;

import com.uniselect.cs.aspect.MetricsCollector;
import com.uniselect.cs.common.constant.SystemRuleConstants;
import com.uniselect.cs.common.dto.SseEvent;
import com.uniselect.cs.common.util.SseEventWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * 网关前置拦截器（核心）。
 *
 * <p>职责（在进入 Controller 之前完成，满足时间预算红线）：
 * <ol>
 *   <li><b>merchant_id 校验</b>：非空 + 格式，< 50ms，<b>不查库</b>；
 *       缺失/非法 → 返回 403 隔离错误 SSE，绝不兜底默认商家（隔离底线）。</li>
 *   <li><b>前置转人工预判</b>：词表 + 正则（平台默认 + 商家追加），< 10ms；
 *       命中 → 直接写 SSE 转人工事件并 {@code return false} 短路，不进 LLM 链路。</li>
 *   <li><b>前置 Prompt 注入预判</b>：词表 + 正则，< 10ms；
 *       命中 → 直接写 {@code event: degrade} 拒绝话术并短路，防注入防线第一层。</li>
 * </ol>
 * </p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>只做字符串操作与 Set 查询，O(1)，严禁查库/远程。</li>
 *   <li>通过 {@link SseEventWriter} 在 preHandle 直接写 SSE，实现真正短路。</li>
 *   <li>不打印用户输入原文全量，仅记录 merchant_id / 命中词 / 耗时（debug）。</li>
 * </ul>
 * </p>
 */
@Component
public class CsGatewayInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CsGatewayInterceptor.class);

    private final PreCheckService preCheckService;
    private final MetricsCollector metricsCollector;
    private final boolean enabled;
    private final long gatewayBudgetNanos;

    public CsGatewayInterceptor(PreCheckService preCheckService,
                                MetricsCollector metricsCollector,
                                @Value("${uniselect.cs.gateway.interceptor-enabled:true}") boolean enabled) {
        this.preCheckService = preCheckService;
        this.metricsCollector = metricsCollector;
        this.enabled = enabled;
        // 网关预算 < 50ms（merchant_id 校验 + 转人工预判合计红线）
        this.gatewayBudgetNanos = TimeUnit.MILLISECONDS.toNanos(50);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enabled) {
            return true;
        }
        long start = System.nanoTime();

        // 健康检查放行：独立探测端点，不承载业务参数，避免被 merchant_id 校验误拦截
        if (request.getRequestURI() != null && request.getRequestURI().endsWith("/health")) {
            return true;
        }

        String merchantId = request.getParameter("merchantId");
        String sessionId = request.getParameter("sessionId");
        String message = request.getParameter("message");

        // 1) merchant_id 严格校验（隔离底线）
        if (!preCheckService.validateMerchantId(merchantId)) {
            metricsCollector.recordMerchantRejected(
                    mask(merchantId), "missing_or_invalid");
            SseEventWriter.writeIsolationError(response,
                    SystemRuleConstants.MERCHANT_ISOLATION_ERROR,
                    "merchant_id 缺失或非法，请求被拒绝");
            log.warn("[gateway] merchant_id rejected: {} (status=403)", mask(merchantId));
            return false;
        }

        // 2) 前置转人工预判（毫秒级短路）
        HandoffDecision decision = preCheckService.predictHandoff(merchantId, message);
        if (decision.hit()) {
            metricsCollector.recordHandoffTriggered(
                    merchantId, sessionId, decision.matchedWord(), decision.elapsedNanos());
            SseEventWriter.writeAndComplete(response,
                    SseEvent.handoff(SystemRuleConstants.HANDOFF_MESSAGE, decision.matchedWord()));
            log.debug("[gateway] handoff short-circuit merchantId={} matched={} costMs={}",
                    merchantId, decision.matchedWord(), decision.elapsedNanos() / 1_000_000.0);
            return false;
        }

        // 3) 前置 Prompt 注入预判（防注入防线第一层：短路拒绝，绝不进 LLM 链路）
        InjectionDecision injection = preCheckService.predictInjection(merchantId, message);
        if (injection.hit()) {
            metricsCollector.recordInjectionBlocked(
                    merchantId, sessionId, injection.matchedWord(), injection.elapsedNanos());
            // 复用 degrade 事件类型承载"降级拒绝"，reason 带 injection 前缀便于审计区分
            SseEventWriter.writeAndComplete(response,
                    new SseEvent("degrade", SystemRuleConstants.PROMPT_INJECTION_MESSAGE,
                            "injection:" + injection.matchedWord()));
            log.warn("[gateway] prompt-injection short-circuit merchantId={} matched={} costMs={}",
                    merchantId, injection.matchedWord(), injection.elapsedNanos() / 1_000_000.0);
            return false;
        }

        long cost = System.nanoTime() - start;
        metricsCollector.recordPassedGateway(merchantId, cost);
        if (cost > gatewayBudgetNanos) {
            log.warn("[gateway] pre-check exceeded 50ms budget: {}ms", cost / 1_000_000.0);
        }
        // 未命中：进入 Controller 业务链路（Step 2~5 逐步接入）
        return true;
    }

    /** 日志脱敏：仅显示前缀，避免泄露完整商家标识（满足评审建议六的脱敏要求） */
    private static String mask(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= 4 ? value : value.substring(0, 4) + "***";
    }
}
