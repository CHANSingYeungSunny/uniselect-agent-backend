package com.uniselect.cs.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uniselect.cs.common.dto.SseEvent;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 向 HttpServletResponse 直接写 SSE 事件的工具。
 *
 * <p>拦截器需要在 {@code preHandle} 阶段短路返回转人工事件，此时尚未进入 Controller，
 * 无法使用 SseEmitter。因此提供本工具以标准 SSE 文本格式直接写响应，实现真正的毫秒级短路。</p>
 *
 * <p>SSE 文本格式（兼容 EventSource）：
 * <pre>
 * event: handoff
 * data: {...json...}
 *
 * </pre>
 * </p>
 */
public final class SseEventWriter {

    private static final Logger log = LoggerFactory.getLogger(SseEventWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SseEventWriter() {
    }

    /**
     * 以 SSE 格式写出单个事件并完成响应（用于拦截器短路场景）。
     *
     * @return 写出是否成功（false 表示响应已提交或 IO 异常）
     */
    public static boolean writeAndComplete(HttpServletResponse response, SseEvent event) {
        if (response.isCommitted()) {
            log.warn("SSE write skipped, response already committed");
            return false;
        }
        try {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/event-stream");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");

            StringBuilder sb = new StringBuilder();
            if (event.type() != null) {
                sb.append("event: ").append(event.type()).append("\n");
            }
            String payload = MAPPER.writeValueAsString(event);
            sb.append("data: ").append(payload).append("\n\n");

            response.getWriter().write(sb.toString());
            response.getWriter().flush();
            response.flushBuffer();
            return true;
        } catch (IOException e) {
            log.warn("SSE write failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 写出错误事件（如 merchant_id 隔离拒绝），HTTP 状态 403。
     */
    public static void writeIsolationError(HttpServletResponse response, String code, String message) {
        if (response.isCommitted()) {
            return;
        }
        try {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/event-stream");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            SseEvent event = SseEvent.error(code, message);
            String payload = MAPPER.writeValueAsString(event);
            response.getWriter().write("event: error\ndata: " + payload + "\n\n");
            response.getWriter().flush();
            response.flushBuffer();
        } catch (IOException e) {
            log.warn("SSE isolation error write failed: {}", e.getMessage());
        }
    }
}
