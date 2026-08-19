/*
 * 文件说明：Hexascope 后端应用启动入口，负责引导 Spring Boot 容器。
 */
package com.hexascope;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Hexascope Agent - TAPD需求审查与评分AI Agent
 *
 * @author Hexascope Team
 */
@SpringBootApplication
@MapperScan("com.hexascope.mapper")
public class HexascopeApplication {

    public static void main(String[] args) {
        SpringApplication.run(HexascopeApplication.class, args);
    }

}
