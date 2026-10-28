<p align="center"><img src="front/public/brand/vita-logo-on-white.svg" width="360" alt="Vita"></p>

# Vita

**Claude Code 세션 기록으로 내 업무를 정리하는 로컬 웹앱.** 주간 계획 → Claude Code 로 진행 → 주간·월간 보고까지 한 곳에서.

> A local web app (macOS) that turns your Claude Code session history into a weekly task board, calendar, AI-written reports and agent-usage analytics. Everything stays on your machine (`127.0.0.1`). UI is in Korean.

Claude Code 는 세션 원본(`~/.claude/projects/**/*.jsonl`)을 30일 뒤 지웁니다. Vita 는 10분마다 그걸 읽어 **내 DB 에 옮겨 적고**,
그 작업들을 **내가 세운 할 일**에 붙여 정리합니다.

## 할 수 있는 것

| 메뉴 | 내용 |
|---|---|
| **업무** `/` | 주·월 할 일(카테고리별 블록, 상태·우선순위·마감·메모), 지난주 이월, 할 일에 안 붙은 "계획 외 작업" |
| **세션** `/sessions` | 1년 잔디, 작업 시간·사용량 카드, 주·월 캘린더(며칠 이어진 세션은 막대로), 세션 목록(할 일별 묶기) |
| **리포트** `/report` | AI 주간·월간 업무 보고서, 사내 주간보고용 글(전체 + 직접 만든 요약 갈래), 모델별 토큰·API 환산 비용 |
| **AI 사용** `/ai` | 에이전트 옵저버 — 도구 실패율·대기·컨텍스트 압축·비용 같은 지표와 병목, 개선 규칙을 `CLAUDE.md`·메모리에 넣고 전후 효과 측정 |
| **설정** `/settings` | 카테고리(이름·순서·색), 저장소 → 카테고리, 반복 업무 세션(배포 등), 주간보고 갈래 |

사이드바엔 지금 열려 있는 Claude Code 세션이 카테고리별로 보입니다.

## 필요한 것

- **macOS** (launchd 로 상주, 키체인·iCloud 로 백업)
- **Java 21** (`/usr/libexec/java_home -v 21` 로 찾음), **Node.js 20+**
- **Claude Code** CLI 로그인 상태 — 요약·리포트는 `claude -p` 로 돌아 **내 Claude 구독**을 씁니다
- git (커밋 수집)

## 설치

```bash
git clone https://github.com/dkflfkd53/vita.git
cd vita
./install.sh        # 빌드 → ~/.claude-worklog/app 에 복사 → launchd 등록(로그인 시 자동 시작)
open http://127.0.0.1:4100
```

처음엔 **설정**에서:

1. **카테고리** — 할 일을 묶을 큰 단위(예: 프로젝트 A / 프로젝트 B / 기타)와 색
2. **저장소 → 카테고리** — 어느 git 저장소(폴더 이름) 세션을 어느 카테고리로 볼지. 비워 두면 모든 저장소를 저장소 이름 그대로 보여 줍니다
3. (선택) **반복 업무 세션** — 배포처럼 늘 돌리는 세션은 계획 외 작업에서 빼고 짧은 이름·색으로
4. (선택) **주간보고 갈래** — "전체 업무 내용" 말고 특정 카테고리·키워드·저장소만 짧게 뽑는 요약. AI 지시와 문체 예시를 적을 수 있고, 직접 고친 글은 다음 주 AI 작성의 예시가 됩니다

그다음 **업무**에서 이번 주 할 일을 적고, 할 일을 펼쳐 "+ 세션 추가"로 열려 있는 Claude Code 세션을 붙이면 됩니다.

```bash
./install.sh     # 코드를 고친 뒤 다시 돌리면 새 jar 로 교체·재시작
./uninstall.sh   # launchd 등록 해제(기록은 남김)
```

로그 `~/.claude-worklog/logs/worklog.log` · 개발 모드 `cd back && ./gradlew bootRun` + `cd front && npm run dev`(5173 → /api 는 4100 으로 프록시)

## 데이터와 개인정보

- 서버는 **127.0.0.1 에만** 열립니다. 외부로 보내는 건 없고, AI 호출은 내 컴퓨터의 `claude -p`(도구 끔, 세션 저장 안 함)뿐입니다.
- 기록은 `~/.claude-worklog/worklog.db`(SQLite) 한 파일. **요청 원문이 들어 있으니 외부에 올리지 마세요.**
- 모으는 것: 세션 제목·작업 시간, 내 요청 원문(앞 2,000자), 수정한 파일 경로, 도구 호출(이름·명령 앞부분·걸린 시간·실패), 토큰 사용량, git 커밋 제목(`--author=<git user.name>`).
- 모으지 않는 것: Claude 답변·생각, 도구 출력, 파일 내용.
- 작업 시간 = 세션 이벤트 간격 15분 이하 구간의 합(추정치). 비용 = API 단가로 환산한 추정치(구독 요금과 다름, 단가는 `application.yml` 의 `worklog.pricing`).

### 백업

- 매일 12:30 스냅샷 → `~/.claude-worklog/backups/*.db.gz` (14일·8주·12개월 보관)
- 같은 파일을 openssl AES-256(PBKDF2) 로 암호화해 iCloud Drive `Worklog-backup` 에 한 부 더. 암호는 키체인 `worklog-backup`
- `./backup-password.sh` 로 암호 확인(따로 보관하세요), `./restore.sh` 로 되돌리기. 새 맥: clone → `./install.sh` → `./restore.sh "<.enc 파일>"`

## 구조

- `back/` Spring Boot 4 · Java 21 · SQLite(JdbcClient) — 수집(`collect`·`usage`·`agent`), 조회·연결(`query`·`work`), 리포트(`report`·`summary`), 설정(`config`)
- `front/` React 19 · Vite 7 · TypeScript · TanStack Query — 빌드 결과를 jar 가 그대로 서빙
- `claude-skill/worklog-publish` 리포트 HTML 을 claude.ai 아티팩트로 올리는 Claude Code 스킬(선택)

화면 이름은 Vita 지만 데이터 폴더(`~/.claude-worklog`)·launchd(`com.worklog.server`)·자바 패키지(`com.worklog`) 이름은 worklog 그대로입니다.

## 라이선스

MIT
