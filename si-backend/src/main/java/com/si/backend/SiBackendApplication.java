package com.si.backend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.si.backend.mapper")
@EnableConfigurationProperties
@EnableScheduling
public class SiBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(SiBackendApplication.class, args);
    }
}
