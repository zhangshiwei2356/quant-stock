package com.quant.stock.config;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP 重接口滑动窗口限流拦截器。
 * <p>
 * 职责：在 {@link WebMvcConfig} 注册的路径上，按「客户端 IP + 请求 URI」统计 60 秒内请求次数。
 * </p>
 * <p>
 * 关键约束：上限来自 {@link QuantProperties#getRateLimitPerMinute()}，≤0 表示关闭限流；
 * 超限时返回 HTTP 429 与 JSON 错误体，不进入 Controller。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class ApiRateLimitInterceptor implements HandlerInterceptor {

    private final QuantProperties props;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<String, Deque<Long>>();

    /**
     * 请求进入 Controller 前执行限流判定。
     *
     * @param request  当前 HTTP 请求（用于 IP 与 URI）
     * @param response 超限时写入 429 与 JSON 消息
     * @param handler  处理器（未使用）
     * @return {@code true} 放行；{@code false} 已写响应并中断链路
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        int limit = props.getRateLimitPerMinute();
        if (limit <= 0) {
            return true;
        }
        String ip = clientIp(request);
        String key = ip + "|" + request.getRequestURI();
        long now = System.currentTimeMillis();
        long windowMs = 60_000L;
        Deque<Long> q = windows.computeIfAbsent(key, k -> new ArrayDeque<Long>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMs) {
                q.pollFirst();
            }
            if (q.size() >= limit) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"rate_limited\",\"message\":\"请求过于频繁，请稍后再试\"}");
                return false;
            }
            q.addLast(now);
        }
        return true;
    }

    /** 优先取 {@code X-Forwarded-For} 首段，否则 {@code getRemoteAddr()}。 */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.trim().isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
