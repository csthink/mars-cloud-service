package com.mars.cloud.service.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


/**
 * 框架使用示例的入口。
 *
 * <p>这个模块的定位是**可运行的文档**：它演示一个最小 mars-cloud 服务需要写哪些东西，
 * 并且它自己参与构建与测试，所以文档不会随框架演进而失效。
 */
@SpringBootApplication
public class SampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }
}
