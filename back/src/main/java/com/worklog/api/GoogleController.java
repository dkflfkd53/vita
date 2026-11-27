package com.worklog.api;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.worklog.google.GoogleCalendarService;
import com.worklog.google.GoogleCalendarService.Config;
import com.worklog.google.GoogleCalendarService.Status;

import lombok.RequiredArgsConstructor;

/** 구글 캘린더 자동 올리기 — 설정, 지금 올리기, .ics 로 이미 가져온 기간 표시 */
@RestController
@RequestMapping("/api/google")
@RequiredArgsConstructor
public class GoogleController {

    private final GoogleCalendarService service;

    @GetMapping
    public Status status() {
        return service.status();
    }

    @PutMapping("/config")
    public Status save(@RequestBody Config body) throws IOException {
        return service.save(body);
    }

    @PostMapping("/sync")
    public Map<String, String> sync() {
        return Map.of("message", service.sync());
    }

    @PostMapping("/mark-imported")
    public Map<String, Integer> markImported(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return Map.of("marked", service.markImported(from, to));
    }
}
