package com.worklog.config;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.worklog.config.Settings.RoutineRule;

import lombok.RequiredArgsConstructor;

/**
 * 배포처럼 늘 하는 일을 돌리는 세션(설정의 "반복 업무 세션"). 할 일 계획 대상이 아니라
 * 계획 외 작업·세션 추가 목록·AI 분류에서 빼고, 화면엔 짧은 이름(label)으로 보인다. 시간·비용 기록은 그대로 남는다.
 */
@Component
@RequiredArgsConstructor
public class RoutineSessions {

    private final SettingsService settings;

    private List<RoutineRule> list() {
        return settings.routines();
    }

    /** 원래 제목(이름 바꾸기 전)으로 찾는다 */
    public Optional<String> labelFor(String originalTitle) {
        if (originalTitle == null) {
            return Optional.empty();
        }
        String t = norm(originalTitle);
        return list().stream().filter(r -> r.title() != null && norm(r.title()).equals(t)).map(RoutineRule::label).findFirst();
    }

    /** 화면 제목이 반복 업무 이름인가 */
    public boolean isRoutine(String displayTitle) {
        if (displayTitle == null) {
            return false;
        }
        String t = norm(displayTitle);
        return list().stream().anyMatch(r -> r.label() != null && norm(r.label()).equals(t));
    }

    /** 캘린더 색 이름(teal 등). 반복 업무가 아니거나 색을 안 정했으면 null — 프로젝트 색을 쓴다 */
    public String colorOf(String displayTitle) {
        if (displayTitle == null) {
            return null;
        }
        String t = norm(displayTitle);
        return list().stream().filter(r -> r.label() != null && norm(r.label()).equals(t)).map(RoutineRule::color)
                .filter(c -> c != null && c.matches("[a-z]+")).findFirst().orElse(null);
    }

    private static String norm(String s) {
        return s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
