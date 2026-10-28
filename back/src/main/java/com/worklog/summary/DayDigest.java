package com.worklog.summary;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import com.worklog.query.Views.CommitView;
import com.worklog.query.Views.DayDetail;
import com.worklog.query.Views.DaySession;
import com.worklog.query.Views.DayTask;
import com.worklog.query.Views.EditView;
import com.worklog.query.Views.PromptView;

/**
 * 하루치 기록을 claude 에 넘길 평문으로 만든다. 요청이 많은 세션은 앞뒤만 남기고, 한 요청도 길이를 자른다.
 */
final class DayDigest {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final int HEAD = 20;
    private static final int TAIL = 20;
    private static final int MAX_FILES = 30;

    private DayDigest() {
    }

    static String render(DayDetail day, ZoneId zone, int promptChars) {
        StringBuilder sb = new StringBuilder();
        for (DayTask task : day.tasks()) {
            for (DaySession s : task.sessions()) {
                sb.append("### 세션 [id: ").append(s.id()).append("] ").append(s.title())
                        .append(" (프로젝트: ").append(task.project() == null ? "-" : task.project())
                        .append(", 작업 ").append(s.activeSec() / 60).append("분)\n");
                List<PromptView> prompts = s.prompts();
                if (!prompts.isEmpty()) {
                    sb.append("사용자 요청:\n");
                    for (int i = 0; i < prompts.size(); i++) {
                        if (prompts.size() > HEAD + TAIL && i == HEAD) {
                            sb.append("- …(중략 ").append(prompts.size() - HEAD - TAIL).append("건)\n");
                            i = prompts.size() - TAIL;
                        }
                        PromptView p = prompts.get(i);
                        sb.append("- ").append(time(p.at(), zone)).append(' ')
                                .append(oneLine(p.text(), promptChars)).append('\n');
                    }
                }
                List<EditView> edits = s.edits();
                if (!edits.isEmpty()) {
                    sb.append("수정 파일: ").append(edits.stream().limit(MAX_FILES)
                            .map(e -> (e.repo() == null ? "" : e.repo() + "/") + e.path())
                            .collect(Collectors.joining(", ")));
                    if (edits.size() > MAX_FILES) {
                        sb.append(" 외 ").append(edits.size() - MAX_FILES).append("개");
                    }
                    sb.append('\n');
                }
                sb.append('\n');
            }
        }
        if (!day.commits().isEmpty()) {
            sb.append("### git 커밋\n");
            for (CommitView c : day.commits()) {
                sb.append("- [").append(c.repo()).append("] ").append(c.subject()).append('\n');
            }
        }
        return sb.toString();
    }

    static String time(String iso, ZoneId zone) {
        return LocalTime.ofInstant(Instant.parse(iso), zone).format(HM);
    }

    static String oneLine(String text, int max) {
        String s = text.replaceAll("\\s+", " ").strip();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
