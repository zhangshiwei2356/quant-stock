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
 * 可选 API Key 鉴权过滤器（Servlet Filter）。
 * <p>
 * 职责：在 {@code quant.api-key} 非空时，校验访问 {@code /api/**} 的请求是否携带正确密钥。
 * </p>
 * <p>
 * 关键约束：{@code /api/config}、{@code /api/schedule} 及非 API 路径不校验；
 * 密钥为空时整过滤器直通。密钥可通过请求头 {@code X-API-Key} 或查询参数 {@code apiKey} 传入。
 * </p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final QuantProperties props;

    /**
     * 每个请求最多执行一次：按需校验 API Key，失败则 401 且不继续 Filter 链。
     *
     * @param request     当前请求
     * @param response    未授权时写入 JSON 401
     * @param filterChain 通过后调用 {@code doFilter} 进入后续过滤器与 DispatcherServlet
     */
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
