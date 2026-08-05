package com.quant.stock.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 扩展配置。
 * <p>
 * 职责：注册根路径重定向与重接口限流拦截器路径。
 * </p>
 * <p>
 * 关键约束：限流仅作用于回测、组合回测、全市场批量扫描三条 API，不改变其它路由行为。
 * </p>
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiRateLimitInterceptor apiRateLimitInterceptor;

    /**
     * 将站点根路径 {@code /} 重定向到前端主页面 {@code /stock.html}。
     *
     * @param registry Spring MVC 视图控制器注册表
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/stock.html");
    }

    /**
     * 挂载 {@link ApiRateLimitInterceptor} 到指定重接口路径。
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiRateLimitInterceptor)
                .addPathPatterns(
                        "/api/backtest/run",
                        "/api/portfolio/run",
                        "/api/batch/scanAllStock",
                        "/api/strategy/*/seed-pool-backtest"
                );
    }
}
