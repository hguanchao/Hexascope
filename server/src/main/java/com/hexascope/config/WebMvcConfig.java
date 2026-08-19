/*
 * 文件说明：Spring Boot 配置类，集中声明安全、跨域、Redis 或 MyBatis 等基础设施。
 */
package com.hexascope.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置（CORS 跨域 + RestTemplate）
 *
 * @author Hexascope Team
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * RestTemplate Bean（用于 TAPD OpenAPI 调用）
     *
     * @return RestTemplate
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * CORS 跨域配置
     *
     * <p>允许前端开发服务器（localhost:15173）访问后端 API。</p>
     *
     * @param registry CorsRegistry
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("http://localhost:15173", "http://localhost:3000")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
