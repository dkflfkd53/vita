package com.worklog.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.worklog.deploy.DeployService;
import com.worklog.deploy.DeployService.Config;
import com.worklog.deploy.DeployService.Event;
import com.worklog.deploy.DeployService.SyncResult;

import lombok.RequiredArgsConstructor;

/** CI/CD 배포 기록 — 설정, 지금 가져오기, 기간 조회 */
@RestController
@RequestMapping("/api/deploys")
@RequiredArgsConstructor
public class DeployController {

    private final DeployService service;

    @GetMapping
    public List<Event> events(@RequestParam String from, @RequestParam String to) {
        return service.events(from, to);
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        Config c = service.configForView();
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        m.put("config", c);
        m.put("lastError", service.lastError());
        m.put("lastSyncAt", service.lastSyncAt());
        return m;
    }

    @PutMapping("/config")
    public SyncResult save(@RequestBody Config config) throws IOException {
        service.save(config);
        return service.sync();
    }

    @PostMapping("/sync")
    public SyncResult sync() {
        return service.sync();
    }
}
