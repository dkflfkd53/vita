package com.worklog.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.worklog.collect.GitCli;

import lombok.RequiredArgsConstructor;

/**
 * 어떤 세션·커밋을 관리 대상으로 볼지와 화면 표시 이름(카테고리). 기준은 설정의 "저장소 → 카테고리" 표.
 * 표가 비어 있으면 전부 관리하고, 표시 이름은 저장소 이름 그대로.
 */
@Component
@RequiredArgsConstructor
public class ProjectScope {

    private final SettingsService settings;
    private final GitCli git;

    private Map<String, String> table() {
        Map<String, String> m = new LinkedHashMap<>();
        settings.projects().forEach(r -> m.put(r.repo(), r.category()));
        return m;
    }

    /** 표에 쓰인 카테고리들(화면의 범위 선택지) */
    public Set<String> labels() {
        return new LinkedHashSet<>(table().values());
    }

    /** 표에 적힌 저장소들(비었으면 빈 집합 = 전부 관리) */
    public Set<String> repos() {
        return table().keySet();
    }

    public boolean managed(String project) {
        return table().isEmpty() || (project != null && table().containsKey(project));
    }

    /** 저장소 이름 → 표시 이름(카테고리). 표에 없으면 그대로 */
    public String label(String project) {
        return project == null ? null : table().getOrDefault(project, project);
    }

    /** 아직 수집 전인 막 연 세션 — 작업 폴더로 판정 */
    public Optional<String> labelForCwd(String cwd) {
        if (cwd == null) {
            return Optional.empty();
        }
        Path dir = Path.of(cwd);
        String repo = git.toplevel(dir).map(p -> String.valueOf(p.getFileName())).orElse(String.valueOf(dir.getFileName()));
        return managed(repo) ? Optional.of(label(repo)) : Optional.empty();
    }
}
