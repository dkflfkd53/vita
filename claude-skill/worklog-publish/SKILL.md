---
name: worklog-publish
description: Vita(로컬 업무 기록 앱, 구 Worklog, http://127.0.0.1:4100)의 주간·월간 리포트를 claude.ai 아티팩트로 올려 공유 링크를 만든다. "리포트 공유", "주간 리포트 올려줘", "/worklog-publish week 2026-W44" 같은 요청에 쓴다.
---

# Vita 리포트 → claude.ai 공유

Vita 앱이 내보낸 리포트 HTML(`~/.claude-worklog/exports/worklog-{kind}-{key}.html`)을 Artifact 로 게시한다.
이 HTML 은 앱이 아티팩트 규칙(제목·색 토큰·다크/라이트·폰 폭·외부 리소스 없음)에 맞춰 만든 것이라 다시 디자인하지 않는다.

## 절차

1. **대상 정하기**
   - 인자가 있으면 그대로: `week 2026-W44` 또는 `month 2026-10`.
   - 없으면 `ls -t ~/.claude-worklog/exports/worklog-*.html | head -1` 로 가장 최근 내보낸 파일.
   - 둘 다 없으면 이번 주(`week`, ISO 주)로 한다.

2. **파일 없으면 내보내기**
   `curl -s -X POST http://127.0.0.1:4100/api/reports/{kind}/{key}/export`
   - `{"message":"먼저 리포트를 생성하세요"}` 가 오면 리포트가 아직 없다는 뜻이다. 사용자에게
     Vita 리포트 화면에서 "AI 리포트 작성"을 먼저 누르라고 안내하고 멈춘다(리포트 생성은 1~3분, 구독 사용량이 든다).
   - 연결 거부면 앱이 꺼진 것 — Vita 저장소 폴더의 `./install.sh` 로 다시 띄우라고 안내한다.

3. **읽기**: 게시 전에 HTML 파일 전체를 Read 로 읽는다. 대화 원문·비밀번호·키·IP 같은 민감정보가 섞였으면 게시하지 말고 사용자에게 알린다.

4. **게시**
   - `~/.claude-worklog/exports/published.json` (`{"week:2026-W44": "<artifact url>"}`) 에 같은 기간 URL 이 있으면
     Artifact `read` 로 그 URL 을 읽은 뒤, 같은 `url` 로 `file_path` 를 publish 해 링크를 유지한다.
   - 없으면 새로 publish: `icon: "report"`, `description` 은 "<기간 라벨> 업무 리포트 — 작업 시간·작업별 진행·커밋 요약".
   - 게시 결과 URL 을 published.json 에 기록한다(파일이 없으면 만든다).

5. **답변**: 링크 한 줄과, 비공개로 올라갔으니 공유는 claude.ai 에서 직접 켜면 된다는 안내만 한다.
