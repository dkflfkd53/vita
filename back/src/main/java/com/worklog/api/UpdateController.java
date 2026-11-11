package com.worklog.api;

import java.io.IOException;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.worklog.update.UpdateService;
import com.worklog.update.UpdateService.Status;

import lombok.RequiredArgsConstructor;

/** 버전 업데이트 — 새 커밋 확인, 받아서 다시 설치 */
@RestController
@RequestMapping("/api/update")
@RequiredArgsConstructor
public class UpdateController {

    private final UpdateService service;

    @GetMapping
    public Status status() {
        return service.status();
    }

    @PostMapping("/check")
    public Status check() {
        return service.check();
    }

    @PostMapping("/apply")
    public Map<String, String> apply() throws IOException {
        return Map.of("message", service.apply());
    }
}
