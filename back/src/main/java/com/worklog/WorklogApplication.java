package com.worklog;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class WorklogApplication {

    public static void main(String[] args) throws Exception {
        // SQLite 는 상위 폴더가 없으면 파일을 못 만든다 — 데이터소스 초기화 전에 만들어 둔다
        String dataDir = System.getenv().getOrDefault("WORKLOG_DATA_DIR",
                System.getProperty("user.home") + "/.claude-worklog");
        Files.createDirectories(Path.of(dataDir, "logs"));
        Files.createDirectories(Path.of(dataDir, "exports"));
        System.setProperty("worklog.data-dir", dataDir);
        SpringApplication.run(WorklogApplication.class, args);
    }
}
