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
 * 对回测/组合/批量等重接口做每 IP 滑动窗口限流。
 */
@Component
@RequiredArgsConstructor
public class ApiRateLimitInterceptor implements HandlerInterceptor {

    private final QuantProperties props;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<String, Deque<Long>>();

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

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.trim().isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
