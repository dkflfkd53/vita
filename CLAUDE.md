# Vita — Claude Code 안내

이 저장소는 Claude Code 세션 기록으로 업무를 정리하는 **로컬 웹앱**이다(서버 `http://127.0.0.1:4100`, 데이터 `~/.claude-worklog`).
사용자가 이 저장소 주소를 주며 "설치해줘", "써 보고 싶어" 라고 하면 아래 **설치 절차**를 따른다.
코드를 고치는 작업이면 맨 아래 **개발**을 본다.

## 설치 절차

사용자에게 진행 상황을 한 줄씩 알리며 차례로 한다. 시스템에 프로그램을 새로 설치하는 명령(brew·winget 등)은 **실행 전에 사용자에게 확인**받는다.

### 1. 운영체제 확인

macOS 면 `uname`, Windows 면 PowerShell(`$PSVersionTable`)로 확인한다. 리눅스는 아직 지원하지 않는다(자동 시작 스크립트가 없음) — 사용자에게 알리고 멈춘다.

### 2. 필요한 것 확인

| 필요 | macOS 확인 | Windows 확인 | 없을 때 설치(사용자 확인 후) |
|---|---|---|---|
| Java 21 이상 | `/usr/libexec/java_home -v 21` | `cmd /c "java -version 2>&1"` | mac `brew install --cask temurin@21` · win `winget install EclipseAdoptium.Temurin.21.JDK` |
| Node.js 20 이상 | `node -v` | `node -v` | mac `brew install node` · win `winget install OpenJS.NodeJS.LTS` |
| git | `git --version` | `git --version` | mac `xcode-select --install` · win `winget install Git.Git` |
| Claude Code CLI | `command -v claude` | `Get-Command claude` | 지금 대화 중인 Claude Code 가 그것이다. 경로만 확인 |

Windows 에서 새로 설치했으면 PATH 가 새 터미널에서만 잡히므로, 이후 명령은 새 PowerShell 에서 돌린다(또는 사용자에게 새 터미널을 열어 달라고 한다).

### 3. 받기

이미 이 저장소 안에서 실행 중이면 건너뛴다. 아니면 사용자에게 받을 위치를 묻고(기본: 홈 아래 `vita`)
`git clone https://github.com/dkflfkd53/vita.git` 후 그 폴더로 들어간다.

### 4. 설치·실행

- macOS: `./install.sh` — 빌드 → `~/.claude-worklog/app` 에 복사 → launchd 등록(로그인 시 자동 시작) → 실행
- Windows: `powershell -ExecutionPolicy Bypass -File .\install.ps1` — 빌드 → `%USERPROFILE%\.claude-worklog\app` → 작업 스케줄러 등록(로그인 시 자동 시작, 막히면 시작 프로그램 바로가기) → 실행

처음 빌드는 Gradle·npm 이 의존성을 내려받아 몇 분 걸린다. 끝에 `실행 중: http://127.0.0.1:4100` 이 나오면 성공.
실패하면 로그를 읽고 원인을 고친 뒤 다시 돌린다: `~/.claude-worklog/logs/worklog.log` (mac 은 `launchd.out` 도).

### 5. 첫 설정 — 사용자와 대화로

설정은 화면 `/settings` 에서도 되지만, 여기서 같이 채워 주면 바로 쓸 수 있다. API 는 모두 `http://127.0.0.1:4100/api/settings`.

1. `GET /api/settings` 로 지금 값을 본다. `knownRepos` = 지금까지 세션에서 본 git 저장소(처음엔 수집 전이라 비어 있을 수 있음 — 10초쯤 뒤 다시).
2. **카테고리**: 할 일을 어떤 큰 단위로 나누는지 묻는다(예: 회사 프로젝트별, 개인). 기본값은 `업무`, `기타`.
   `PUT /api/settings/categories` 본문 = 순서대로 `[{"name":"프로젝트 A","color":"blue","oldName":"업무"},{"name":"기타","color":null,"oldName":"기타"}]`.
   `oldName` 은 기존 카테고리 이름을 바꿀 때만(할 일도 따라 바뀜). 색: gray·blue·green·yellow·red·purple·orange·teal·pink 또는 null(자동).
3. **저장소 → 카테고리**: `knownRepos` 를 보여 주고 어느 저장소가 어느 카테고리인지 묻는다.
   `PUT /api/settings/projects` 본문 = `[{"repo":"my-app","category":"프로젝트 A"}]`. **비워 두면 모든 저장소를 관리**하고, 하나라도 적으면 적은 것만 관리한다.
4. (선택) **반복 업무 세션** — 배포처럼 늘 돌리는 세션이 있으면: `PUT /api/settings/routines` 본문 = `[{"title":"원래 세션 제목","label":"표시 이름","color":"teal"}]`.
5. (선택) **주간보고 갈래** — 사내 주간보고 양식이 있으면 화면 `/settings` 의 "주간보고 갈래"에서 지시·문체 예시를 적도록 안내한다(직접 고친 글이 다음 주 AI 작성의 예시가 됨).

### 6. 열기와 안내

브라우저로 연다(mac `open http://127.0.0.1:4100`, win `start http://127.0.0.1:4100`). 그리고 짧게 알린다:

- **업무**에서 이번 주 할 일을 적고, 할 일을 펼쳐 "+ 세션 추가"로 Claude Code 세션을 붙인다. 세션은 10분마다 자동으로 모인다.
- AI 요약·리포트는 이 컴퓨터의 `claude -p` 로 돌아 **사용자의 Claude 구독**을 쓴다.
- 기록은 `~/.claude-worklog/worklog.db` 한 파일(요청 원문 포함) — 외부에 올리지 말 것. 백업은 매일 `~/.claude-worklog/backups` (macOS 는 iCloud 암호화 사본도).
- 업데이트: 저장소 폴더에서 `git pull` 후 설치 스크립트를 다시 실행. 제거: `./uninstall.sh` / `.\uninstall.ps1` (기록은 남음).

## 개발

- `back/` Spring Boot 4 · Java 21 · SQLite(JdbcClient). 빌드 확인 `cd back && ./gradlew compileJava -q`, 테스트 `./gradlew test`
- `front/` React 19 · Vite 7 · TypeScript. 타입 확인 `cd front && npx tsc -b --noEmit`
- 개발 모드: `cd back && ./gradlew bootRun` + `cd front && npm run dev`(5173, `/api` 는 4100 으로 프록시)
- 고친 뒤 실제 앱에 반영: `./install.sh` / `.\install.ps1`
- 사용자별 값(카테고리·저장소·반복 업무·주간보고 갈래·문체 예시)은 **DB 설정(`setting` 표)** 에 둔다. 특정 회사·개인 값을 코드나 `application.yml` 에 넣지 않는다.
- 새 화면 경로를 만들면 `SpaForwardController` 에도 추가한다(새로고침 시 404 방지).
