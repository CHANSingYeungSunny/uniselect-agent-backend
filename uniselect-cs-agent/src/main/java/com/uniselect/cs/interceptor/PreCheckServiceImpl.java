package com.uniselect.cs.interceptor;

import com.uniselect.cs.common.constant.SystemRuleConstants;
import com.uniselect.cs.common.util.PromptInjectionGuard;
import com.uniselect.cs.service.MerchantConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 前置检查实现。
 *
 * <p>性能红线：
 * <ul>
 *   <li>validateMerchantId：纯正则，O(1)，< 50ms（实际亚毫秒）</li>
 *   <li>predictHandoff：Set 精确匹配 + 少量正则，< 10ms（含商家追加词）</li>
 * </ul>
 * 任何一步都不得查库或发起远程调用。
 * </p>
 */
@Service
public class PreCheckServiceImpl implements PreCheckService {

    private static final Logger log = LoggerFactory.getLogger(PreCheckServiceImpl.class);

    private final MerchantConfigService merchantConfigService;
    private final Pattern merchantIdPattern;
    private final long predictTimeoutNanos;
    /** 平台默认正则预编译缓存，避免每次请求重复 compile（P95 优化） */
    private final List<Pattern> compiledDefaultPatterns;

    public PreCheckServiceImpl(MerchantConfigService merchantConfigService,
                               @Value("${uniselect.cs.gateway.merchant-id-pattern}") String merchantIdPatternStr,
                               @Value("${uniselect.cs.handoff.predict-timeout-ms}") long predictTimeoutMs) {
        this.merchantConfigService = merchantConfigService;
        this.merchantIdPattern = Pattern.compile(merchantIdPatternStr);
        this.predictTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(predictTimeoutMs);
        this.compiledDefaultPatterns = SystemRuleConstants.DEFAULT_HANDOFF_PATTERNS.stream()
                .map(Pattern::compile)
                .collect(Collectors.toList());
    }

    @Override
    public InjectionDecision predictInjection(String merchantId, String message) {
        if (message == null || message.isBlank()) {
            return InjectionDecision.miss(0L);
        }
        long start = System.nanoTime();
        // 词表精确子串 + 正则变形，统一规则源（PromptInjectionGuard），< 10ms
        String matched = PromptInjectionGuard.match(message);
        if (matched != null) {
            return InjectionDecision.hit(matched, elapsed(start));
        }
        long cost = elapsed(start);
        if (cost > predictTimeoutNanos) {
            log.warn("injection predict exceeded soft timeout, merchantId={}, costNs={}", merchantId, cost);
        }
        return InjectionDecision.miss(cost);
    }

    @Override
    public boolean validateMerchantId(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            return false;
        }
        // 纯格式校验，不查库
        return merchantIdPattern.matcher(merchantId).matches();
    }

    @Override
    public HandoffDecision predictHandoff(String merchantId, String message) {
        if (message == null || message.isBlank()) {
            return HandoffDecision.miss(0L);
        }
        long start = System.nanoTime();

        // 1) 平台默认词表（精确匹配，不可被关闭）
        if (SystemRuleConstants.DEFAULT_HANDOFF_KEYWORDS.contains(message)
                || containsAnyKeyword(message, SystemRuleConstants.DEFAULT_HANDOFF_KEYWORDS)) {
            return HandoffDecision.hit("DEFAULT_KEYWORD", elapsed(start));
        }

        // 2) 平台默认正则（使用预编译对象）
        for (Pattern p : compiledDefaultPatterns) {
            if (p.matcher(message).find()) {
                return HandoffDecision.hit("PATTERN:" + p.pattern(), elapsed(start));
            }
        }

        // 3) 商家追加词（可追加，不可删除默认词）
        Set<String> merchantExtra = merchantConfigService.getHandoffKeywords(merchantId);
        if (containsAnyKeyword(message, merchantExtra)) {
            return HandoffDecision.hit("MERCHANT_KEYWORD", elapsed(start));
        }

        // 超时保护：若超过兜底阈值，视为未命中（避免极端情况阻塞主链路）
        long cost = elapsed(start);
        if (cost > predictTimeoutNanos) {
            log.warn("handoff predict exceeded soft timeout, merchantId={}, costNs={}", merchantId, cost);
        }
        return HandoffDecision.miss(cost);
    }

    private static boolean containsAnyKeyword(String message, Set<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        // 子串包含匹配，覆盖"我要退款""转人工处理"等组合
        for (String kw : keywords) {
            if (message.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private long elapsed(long startNanos) {
        return System.nanoTime() - startNanos;
    }
}
