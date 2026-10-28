package com.worklog.config;

import java.util.List;

/** 화면의 설정에서 바꾸는 값 */
public final class Settings {

    private Settings() {
    }

    /** color = 라벨·막대 색 이름(gray·blue·green·yellow·red·purple·orange·teal·pink), null 이면 이름으로 자동 */
    public record Category(String name, String color, String oldName) {
    }

    /** 이 git 저장소(폴더 이름)에서 만든 세션·커밋을 이 카테고리로 본다 */
    public record ProjectRule(String repo, String category) {
    }

    /** 반복 업무(배포 등) — 원래 세션 제목이 title 이면 label 로 보이고 계획 외 작업에서 빠진다 */
    public record RoutineRule(String title, String label, String color) {
    }

    /**
     * 주간보고 글 갈래. 전체(full)는 늘 있고, 나머지는 사용자가 만든 요약 갈래.
     * source = items(할 일 기준: categories·keywords 로 거름) | commits(repos 의 커밋 기준)
     * guide = AI 에게 줄 형식 지시, example = 문체 예시
     */
    public record WorkTextPart(String id, String title, String source, List<String> categories, List<String> keywords,
                               List<String> repos, Integer maxLines, String guide, String example) {
    }

    public record WorkTextConfig(WorkTextPart full, List<WorkTextPart> parts) {
    }

    public record All(List<Category> categories, List<ProjectRule> projects, List<RoutineRule> routines,
                      WorkTextConfig workText, List<String> knownRepos) {
    }
}
