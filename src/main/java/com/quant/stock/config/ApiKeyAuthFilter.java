package com.quant.stock.config;

import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 可选 API Key：{@code quant.api-key} 非空时，/api/** 须带匹配的 X-API-Key。
 * 静态资源与 /actuator/health 放行。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final QuantProperties props;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path == null) {
            path = "";
        }
        if (!path.startsWith("/api/") || path.startsWith("/api/config")
                || path.startsWith("/api/schedule")
                || !StringUtils.hasText(props.getApiKey())) {
            filterChain.doFilter(request, response);
            return;
        }
        String key = request.getHeader("X-API-Key");
        if (key == null) {
            key = request.getParameter("apiKey");
        }
        if (props.getApiKey().equals(key)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"缺少或错误的 X-API-Key\"}");
    }
}
