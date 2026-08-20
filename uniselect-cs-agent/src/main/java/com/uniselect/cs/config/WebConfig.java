package com.uniselect.cs.config;

import com.uniselect.cs.interceptor.CsGatewayInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：将 CsGatewayInterceptor 注册到客服对话入口路径。
 * 拦截 /api/cs/** 与 /api/shopping/**（导购复用同一网关隔离 + 转人工/注入短路），
 * 不影响健康检查等其它端点。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CsGatewayInterceptor csGatewayInterceptor;

    public WebConfig(CsGatewayInterceptor csGatewayInterceptor) {
        this.csGatewayInterceptor = csGatewayInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(csGatewayInterceptor)
                .addPathPatterns("/api/cs/**", "/api/shopping/**")
                .order(0); // 最高优先级，确保最先执行
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Demo 用途：允许任意来源（含 file:// 双击打开 demo.html）直连 SSE 接口。
        // 生产环境务必收紧为明确的域名白名单。
        registry.addMapping("/api/cs/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
        registry.addMapping("/api/shopping/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}
