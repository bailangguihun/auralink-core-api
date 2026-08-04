package com.auralink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties
public class Application {

    @PostConstruct
    void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));

        // 创建必要的目录
        new File("./temp_uploads").mkdirs();
        new File("../frontend/public/audios").mkdirs();
    }

    // 移除了额外的CORS配置，统一使用CustomCorsFilter

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
