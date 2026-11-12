package com.worklog.api;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.worklog.export.CalendarExportService;

import lombok.RequiredArgsConstructor;

/** 세션 캘린더 내보내기(.ics) — 구글 캘린더 «가져오기»용 */
@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class ExportController {

    private final CalendarExportService calendar;

    @GetMapping("/calendar.ics")
    public ResponseEntity<byte[]> calendar(@RequestParam String from, @RequestParam(required = false) String to) {
        LocalDate f = LocalDate.parse(from);
        LocalDate t = to == null || to.isBlank() ? LocalDate.now() : LocalDate.parse(to);
        byte[] body = calendar.ics(f, t).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "calendar", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vita-" + f + "_" + t + ".ics\"")
                .body(body);
    }
}
