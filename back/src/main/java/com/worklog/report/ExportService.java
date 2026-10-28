package com.worklog.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Service;

import com.worklog.config.WorklogProperties;
import com.worklog.query.Rows.ReportRow;
import com.worklog.query.Views.HeatDay;
import com.worklog.query.Views.PeriodStats;
import com.worklog.query.Views.ProjectStat;
import com.worklog.query.WorkQueryService;

import lombok.RequiredArgsConstructor;

/**
 * 리포트를 외부 리소스 없는 HTML 한 장으로 내보낸다. claude.ai 아티팩트로 올려 링크 공유하는 용도라
 * 아티팩트 규칙(색 토큰·다크/라이트 모드·폰 폭)을 따른다. 업로드는 Claude Code 의 worklog-publish 스킬이 한다.
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    private static final List<org.commonmark.Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS).escapeHtml(true).build();

    private final WorklogProperties props;
    private final ReportService reports;
    private final WorkQueryService query;

    public Path export(Period period, String templateId) throws IOException {
        ReportRow report = reports.get(period, templateId)
                .orElseThrow(() -> new IllegalStateException("먼저 리포트를 생성하세요"));
        PeriodStats stats = query.stats(period.from(), period.to());
        boolean custom = templateId != null && !templateId.isBlank() && !"default".equals(templateId);
        Files.createDirectories(props.exportsDir());
        Path file = props.exportsDir().resolve("worklog-" + period.kind() + "-" + period.key() + (custom ? "-" + templateId : "") + ".html");
        Files.writeString(file, render(period, reports.title(period, templateId), report, stats), StandardCharsets.UTF_8);
        return file;
    }

    String render(Period period, String title, ReportRow report, PeriodStats stats) {
        String body = RENDERER.render(PARSER.parse(report.content()));
        long maxProject = stats.projects().stream().mapToLong(ProjectStat::activeSec).max().orElse(1);
        StringBuilder projects = new StringBuilder();
        for (ProjectStat p : stats.projects()) {
            if (p.activeSec() == 0 && p.commits() == 0) {
                continue;
            }
            double pct = maxProject == 0 ? 0 : 100.0 * p.activeSec() / maxProject;
            projects.append("""
                    <div class="bar-row"><span class="bar-label">%s</span>
                    <span class="bar-track"><span class="bar-fill" style="width:%.1f%%"></span></span>
                    <span class="bar-value">%s시간 · 커밋 %d</span></div>
                    """.formatted(esc(p.project()), pct, ReportService.hours(p.activeSec()), p.commits()));
        }
        long maxDay = stats.days().stream().mapToLong(HeatDay::activeSec).max().orElse(1);
        StringBuilder days = new StringBuilder();
        for (HeatDay d : stats.days()) {
            double pct = maxDay == 0 ? 0 : Math.max(d.activeSec() > 0 ? 4 : 0, 100.0 * d.activeSec() / maxDay);
            LocalDate date = LocalDate.parse(d.day());
            days.append("""
                    <div class="day" title="%s · %s시간 · 커밋 %d"><span class="day-bar" style="height:%.1f%%"></span><span class="day-label">%d</span></div>
                    """.formatted(d.day(), ReportService.hours(d.activeSec()), d.commits(), pct, date.getDayOfMonth()));
        }
        return TEMPLATE
                .replace("{{title}}", esc(title))
                .replace("{{period}}", esc(period.label()))
                .replace("{{range}}", period.from() + " ~ " + period.to())
                .replace("{{hours}}", ReportService.hours(stats.activeSec()))
                .replace("{{activeDays}}", String.valueOf(stats.activeDays()))
                .replace("{{tasks}}", String.valueOf(stats.taskCount()))
                .replace("{{commits}}", String.valueOf(stats.commits()))
                .replace("{{projects}}", projects.toString())
                .replace("{{days}}", days.toString())
                .replace("{{body}}", body)
                .replace("{{generatedAt}}", report.generatedAt().substring(0, 10));
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String TEMPLATE = """
            <!doctype html>
            <html lang="ko">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>{{title}}</title>
            <style>
            :root {
              --bg: #ffffff; --surface: #f6f8fa; --border: #d0d7de; --text: #1f2328; --muted: #59636e;
              --accent: #c15f3c; --accent-soft: #f6e3da; --bar: #c15f3c; --track: #eaeef2;
            }
            @media (prefers-color-scheme: dark) {
              :root:not([data-theme="light"]) {
                --bg: #0d1117; --surface: #161b22; --border: #30363d; --text: #e6edf3; --muted: #9198a1;
                --accent: #e08a68; --accent-soft: #3a2219; --bar: #d97757; --track: #21262d;
              }
            }
            :root[data-theme="dark"] {
              --bg: #0d1117; --surface: #161b22; --border: #30363d; --text: #e6edf3; --muted: #9198a1;
              --accent: #e08a68; --accent-soft: #3a2219; --bar: #d97757; --track: #21262d;
            }
            * { box-sizing: border-box; }
            body { margin: 0; background: var(--bg); color: var(--text);
              font: 15px/1.7 -apple-system, BlinkMacSystemFont, "Apple SD Gothic Neo", "Pretendard", "Segoe UI", sans-serif; }
            main { max-width: 860px; margin: 0 auto; padding: 40px 16px 64px; }
            .eyebrow { color: var(--accent); font-size: 13px; font-weight: 600; letter-spacing: .02em; }
            h1 { font-size: 28px; line-height: 1.3; margin: 6px 0 4px; }
            .range { color: var(--muted); font-size: 14px; margin: 0 0 28px; }
            .stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 28px; }
            .stat { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; }
            .stat b { display: block; font-size: 24px; font-variant-numeric: tabular-nums; }
            .stat span { color: var(--muted); font-size: 13px; }
            section.panel { border: 1px solid var(--border); border-radius: 10px; padding: 18px 20px; margin-bottom: 20px; }
            section.panel h2 { font-size: 14px; color: var(--muted); margin: 0 0 12px; font-weight: 600; }
            .bar-row { display: grid; grid-template-columns: 120px 1fr auto; gap: 12px; align-items: center; font-size: 13px; padding: 4px 0; }
            .bar-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
            .bar-track { height: 8px; background: var(--track); border-radius: 4px; overflow: hidden; }
            .bar-fill { display: block; height: 100%; background: var(--bar); border-radius: 4px; }
            .bar-value { color: var(--muted); font-variant-numeric: tabular-nums; }
            .days { display: flex; gap: 3px; align-items: flex-end; height: 90px; }
            .day { flex: 1; display: flex; flex-direction: column; justify-content: flex-end; align-items: center; height: 100%; min-width: 0; }
            .day-bar { width: 100%; max-width: 28px; background: var(--bar); border-radius: 3px 3px 0 0; }
            .day-label { font-size: 10px; color: var(--muted); margin-top: 4px; }
            article h2 { font-size: 20px; margin: 36px 0 12px; padding-bottom: 6px; border-bottom: 1px solid var(--border); }
            article h3 { font-size: 16px; margin: 24px 0 6px; }
            article p { margin: 6px 0; }
            article ul { padding-left: 20px; margin: 6px 0; }
            article li { margin: 3px 0; }
            article code { background: var(--surface); border: 1px solid var(--border); border-radius: 5px; padding: 0 5px; font-size: 13px; }
            article table { border-collapse: collapse; width: 100%; font-size: 14px; }
            article th, article td { border: 1px solid var(--border); padding: 6px 10px; text-align: left; }
            footer { color: var(--muted); font-size: 12px; margin-top: 48px; }
            @media (max-width: 640px) {
              .stats { grid-template-columns: repeat(2, 1fr); }
              .bar-row { grid-template-columns: 80px 1fr; }
              .bar-value { grid-column: 2; }
              h1 { font-size: 23px; }
            }
            </style>
            </head>
            <body>
            <main>
              <div class="eyebrow">{{title}}</div>
              <h1>{{period}}</h1>
              <p class="range">{{range}}</p>
              <div class="stats">
                <div class="stat"><b>{{hours}}</b><span>작업 시간(h)</span></div>
                <div class="stat"><b>{{activeDays}}</b><span>일한 날</span></div>
                <div class="stat"><b>{{tasks}}</b><span>작업</span></div>
                <div class="stat"><b>{{commits}}</b><span>커밋</span></div>
              </div>
              <section class="panel"><h2>프로젝트별 작업 시간</h2>{{projects}}</section>
              <section class="panel"><h2>날짜별 작업 시간</h2><div class="days">{{days}}</div></section>
              <article>{{body}}</article>
              <footer>Vita · 작업 시간은 AI 에이전트 세션 활동 기준 추정치 · {{generatedAt}} 생성</footer>
            </main>
            </body>
            </html>
            """;
}
