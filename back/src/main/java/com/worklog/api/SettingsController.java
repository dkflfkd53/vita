package com.worklog.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.worklog.config.Settings;
import com.worklog.config.Settings.Category;
import com.worklog.config.Settings.ProjectRule;
import com.worklog.config.Settings.RoutineRule;
import com.worklog.config.Settings.WorkTextConfig;
import com.worklog.config.SettingsService;

import lombok.RequiredArgsConstructor;

/** 화면의 설정 — 카테고리, 저장소 → 카테고리, 반복 업무 세션, 주간보고 갈래 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settings;

    @GetMapping
    public Settings.All all() {
        return settings.all();
    }

    @PutMapping("/categories")
    public ResponseEntity<Void> categories(@RequestBody List<Category> list) {
        settings.saveCategories(list);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/projects")
    public ResponseEntity<Void> projects(@RequestBody List<ProjectRule> list) {
        settings.saveProjects(list);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/routines")
    public ResponseEntity<Void> routines(@RequestBody List<RoutineRule> list) {
        settings.saveRoutines(list);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/ai")
    public ResponseEntity<Void> ai(@RequestBody Settings.AiConfig ai) {
        settings.saveAi(ai);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/work-text")
    public ResponseEntity<Void> workText(@RequestBody WorkTextConfig config) {
        settings.saveWorkText(config);
        return ResponseEntity.noContent().build();
    }
}
