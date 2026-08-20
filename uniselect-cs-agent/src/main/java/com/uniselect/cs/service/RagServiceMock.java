package com.uniselect.cs.service;

import com.uniselect.cs.common.dto.RagChunk;
import com.uniselect.cs.common.dto.RagRetrieveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * RagService 的 Mock 实现（内存向量检索占位）。
 *
 * <p>用<b>简化词袋相似度</b>模拟向量召回（真实实现替换为 pgvector 余弦距离），
 * 按 merchant_id 命名空间分桶，保证隔离。模拟 ~30~80ms 检索延迟，
 * 并保留 100ms 超时降级空结果的能力（通过 {@code retrieveWithinBudget} 实现）。</p>
 */
@Service
@Profile("mock")
public class RagServiceMock implements RagService {

    private static final Logger log = LoggerFactory.getLogger(RagServiceMock.class);

    /** merchant_id -> 文档片段（静态层：规格/政策/FAQ） */
    private final Map<String, List<RagChunk>> index = new ConcurrentHashMap<>();

    private final long retrieveTimeoutNanos;

    public RagServiceMock(@Value("${uniselect.cs.rag.timeout-ms:100}") long ragTimeoutMs) {
        this.retrieveTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(ragTimeoutMs);
        seed();
    }

    /** 内置示例知识（仅静态层字段，不含库存/价格） */
    private void seed() {
        index.put("M-1001", List.of(
                new RagChunk("M-1001", "kb-M-1001-spec", "校园保温杯 500ml，316 不锈钢内胆，保温 12 小时，防漏杯盖。", 0),
                new RagChunk("M-1001", "kb-M-1001-spec", "通勤包 容量 18L，防泼水面料，含电脑隔层。", 0),
                new RagChunk("M-1001", "kb-M-1001-policy", "退换货：签收 7 日内可无理由退换，需保持商品完好。", 0),
                new RagChunk("M-1001", "kb-M-1001-policy", "运费：满 99 元包邮，偏远地区补 10 元。", 0),
                new RagChunk("M-1001", "kb-M-1001-faq", "发货时效：当日 16 点前下单，当天发出。", 0)
        ));
        index.put("M-1002", List.of(
                new RagChunk("M-1002", "kb-M-1002-spec", "运动水壶 750ml，Tritan 材质，可装温水。", 0)
        ));
    }

    @Override
    public RagRetrieveResult retrieve(String merchantId, String query, int topK) {
        long start = System.nanoTime();
        try {
            // 模拟检索延迟（真实为向量召回耗时）
            Thread.sleep(30 + (int) (Math.random() * 50));

            // 超时降级：超过预算返回降级标记（区分"无命中"与"超时"，便于 Prompt 标注）
            if (System.nanoTime() - start > retrieveTimeoutNanos) {
                log.warn("[rag] retrieve exceeded {}ms budget, degrade, merchantId={}",
                        TimeUnit.NANOSECONDS.toMillis(retrieveTimeoutNanos), merchantId);
                return RagRetrieveResult.degraded("TIMEOUT");
            }

            List<RagChunk> docs = index.getOrDefault(merchantId, List.of());
            if (docs.isEmpty()) {
                return RagRetrieveResult.empty();
            }
            // 词袋相似度打分（Mock）
            List<RagChunk> scored = docs.stream()
                    .map(c -> new RagChunk(c.merchantId(), c.docId(), c.content(),
                            score(query, c.content())))
                    .filter(c -> c.score() > 0)
                    .sorted(Comparator.comparingDouble(RagChunk::score).reversed())
                    .limit(topK)
                    .collect(Collectors.toList());
            return scored.isEmpty() ? RagRetrieveResult.empty() : RagRetrieveResult.hit(scored);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RagRetrieveResult.degraded("INTERRUPTED");
        }
    }

    /** 极简词袋重叠度（0~1）：两文本共有词数 / 查询词数 */
    private double score(String query, String content) {
        String[] qTokens = query.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", " ").split("\\s+");
        if (qTokens.length == 0) {
            return 0;
        }
        int hit = 0;
        for (String t : qTokens) {
            if (!t.isBlank() && content.contains(t)) {
                hit++;
            }
        }
        return (double) hit / qTokens.length;
    }
}
