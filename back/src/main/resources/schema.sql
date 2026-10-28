PRAGMA journal_mode=WAL;

-- 세션 jsonl 을 어디까지 읽었는지. 파일이 줄어들면(재작성) 처음부터 다시 읽는다
CREATE TABLE IF NOT EXISTS ingest_file (
    path        TEXT PRIMARY KEY,
    read_offset INTEGER NOT NULL,
    updated_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS session (
    id             TEXT PRIMARY KEY,
    custom_title   TEXT,
    ai_title       TEXT,
    first_prompt   TEXT,
    title_override TEXT,
    cwd            TEXT,
    project        TEXT,
    git_branch     TEXT,
    continued_in   TEXT,
    first_at       TEXT,
    last_at        TEXT,
    last_event_at  TEXT,
    hidden         INTEGER NOT NULL DEFAULT 0
);

-- 세션 × 날짜(로컬). active_sec = 이벤트 간격이 idle-gap 이하인 구간의 합
CREATE TABLE IF NOT EXISTS session_day (
    session_id TEXT NOT NULL,
    day        TEXT NOT NULL,
    first_at   TEXT,
    last_at    TEXT,
    active_sec INTEGER NOT NULL DEFAULT 0,
    prompts    INTEGER NOT NULL DEFAULT 0,
    edits      INTEGER NOT NULL DEFAULT 0,
    summary    TEXT,
    PRIMARY KEY (session_id, day)
);
CREATE INDEX IF NOT EXISTS idx_session_day_day ON session_day (day);

-- 원본이 30일 뒤 지워져도 남도록 사용자 요청 원문을 옮겨 둔다
CREATE TABLE IF NOT EXISTS prompt (
    uuid       TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    at         TEXT NOT NULL,
    day        TEXT NOT NULL,
    text       TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_prompt_session ON prompt (session_id, at);
CREATE INDEX IF NOT EXISTS idx_prompt_day ON prompt (day);

CREATE TABLE IF NOT EXISTS file_edit (
    tool_use_id TEXT PRIMARY KEY,
    session_id  TEXT NOT NULL,
    at          TEXT NOT NULL,
    day         TEXT NOT NULL,
    tool        TEXT NOT NULL,
    path        TEXT NOT NULL,
    repo        TEXT
);
CREATE INDEX IF NOT EXISTS idx_file_edit_day ON file_edit (day);
CREATE INDEX IF NOT EXISTS idx_file_edit_session ON file_edit (session_id);

-- 세션 cwd·수정 파일에서 찾아낸 git 저장소. 커밋 수집 대상
CREATE TABLE IF NOT EXISTS repo (
    root TEXT PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS git_commit (
    repo    TEXT NOT NULL,
    hash    TEXT NOT NULL,
    at      TEXT NOT NULL,
    day     TEXT NOT NULL,
    subject TEXT NOT NULL,
    PRIMARY KEY (repo, hash)
);
CREATE INDEX IF NOT EXISTS idx_git_commit_day ON git_commit (day);

CREATE TABLE IF NOT EXISTS day_summary (
    day          TEXT PRIMARY KEY,
    overview     TEXT NOT NULL,
    model        TEXT,
    generated_at TEXT NOT NULL
);

-- kind = week | month, period_key = 2026-W44 | 2026-10
CREATE TABLE IF NOT EXISTS report (
    kind         TEXT NOT NULL,
    period_key   TEXT NOT NULL,
    content      TEXT NOT NULL,
    model        TEXT,
    generated_at TEXT NOT NULL,
    PRIMARY KEY (kind, period_key)
);

-- ───────── 주간 업무 계획 ─────────
CREATE TABLE IF NOT EXISTS category (
    name TEXT PRIMARY KEY,
    sort INTEGER NOT NULL DEFAULT 0
);
-- 처음 한 번만 기본 카테고리(설정에서 이름·순서·색을 바꾼다)
INSERT INTO category (name, sort) SELECT '업무', 1 WHERE NOT EXISTS (SELECT 1 FROM category);
INSERT INTO category (name, sort) SELECT '기타', 9 WHERE (SELECT COUNT(*) FROM category) = 1 AND NOT EXISTS (SELECT 1 FROM category WHERE name = '기타');

-- 업무는 주를 넘어 이어진다. 어느 주 계획에 들어 있는지는 week_plan 이 가진다
-- priority = HIGH | MEDIUM | LOW, status = PLANNED | IN_PROGRESS | DONE | ON_HOLD
CREATE TABLE IF NOT EXISTS work_item (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    title      TEXT NOT NULL,
    category   TEXT,
    priority   TEXT NOT NULL DEFAULT 'MEDIUM',
    status     TEXT NOT NULL DEFAULT 'PLANNED',
    due_date   TEXT,
    requester  TEXT,
    progress   INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    done_at    TEXT
);

CREATE TABLE IF NOT EXISTS week_plan (
    week_key     TEXT NOT NULL,
    work_item_id INTEGER NOT NULL,
    added_at     TEXT NOT NULL,
    PRIMARY KEY (week_key, work_item_id)
);

-- 세션 × 날짜 → 업무. work_item_id NULL = 계획 외로 판단됨. source = auto(AI) | manual(사용자)
-- 사용자가 고친 줄(manual)은 AI 재분류가 덮어쓰지 않는다
CREATE TABLE IF NOT EXISTS work_link (
    session_id   TEXT NOT NULL,
    day          TEXT NOT NULL,
    work_item_id INTEGER,
    source       TEXT NOT NULL,
    linked_at    TEXT NOT NULL,
    PRIMARY KEY (session_id, day)
);
CREATE INDEX IF NOT EXISTS idx_work_link_item ON work_link (work_item_id);

-- "이 세션은 항상 이 업무" — 앞으로 생기는 날짜도 AI 없이 이 업무로 본다
CREATE TABLE IF NOT EXISTS session_pin (
    session_id   TEXT PRIMARY KEY,
    work_item_id INTEGER NOT NULL
);

-- ───────── 토큰 사용량 ─────────
-- Claude 응답 하나 = 한 행. 같은 응답이 여러 줄로 기록되므로 message.id 로 합친다(필드별 최댓값)
-- 서브에이전트 응답도 sessionId 가 부모 세션이라 그대로 부모에 붙는다(sidechain = 1)
CREATE TABLE IF NOT EXISTS usage_event (
    message_id     TEXT PRIMARY KEY,
    session_id     TEXT NOT NULL,
    day            TEXT NOT NULL,
    model          TEXT NOT NULL,
    input_tokens   INTEGER NOT NULL,
    output_tokens  INTEGER NOT NULL,
    cache_read     INTEGER NOT NULL,
    cache_write_5m INTEGER NOT NULL,
    cache_write_1h INTEGER NOT NULL,
    sidechain      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_usage_day ON usage_event (day);
CREATE INDEX IF NOT EXISTS idx_usage_session ON usage_event (session_id, day);

-- 사용량은 세션 수집과 따로 읽는다 — 처음 붙일 때 원본 전체를 다시 읽어도 작업 시간이 이중으로 쌓이지 않게
CREATE TABLE IF NOT EXISTS ingest_usage_file (
    path        TEXT PRIMARY KEY,
    read_offset INTEGER NOT NULL,
    updated_at  TEXT NOT NULL
);

-- ───────── 에이전트 관찰(Agent Observer) ─────────
-- 도구 호출 한 번 = 한 행. tool_use 줄에서 만들고 tool_result 줄에서 끝 시각·결과를 채운다
-- 걸린 시간 = ended_at - started_at (승인 대기·질문 응답 대기가 섞여 있다 — 화면에서 도구 종류로 나눠 본다)
CREATE TABLE IF NOT EXISTS agent_tool_call (
    tool_use_id TEXT PRIMARY KEY,
    session_id  TEXT NOT NULL,
    day         TEXT NOT NULL,
    started_at  TEXT NOT NULL,
    ended_at    TEXT,
    tool        TEXT NOT NULL,
    target      TEXT,
    status      TEXT,
    error_text  TEXT,
    sidechain   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tool_call_day ON agent_tool_call (day);

-- turn(Claude 한 턴, value_ms = 턴 길이) | compact(컨텍스트 압축, detail = auto|manual:압축 전 토큰) | interrupt(사용자 중단)
CREATE TABLE IF NOT EXISTS agent_event (
    session_id TEXT NOT NULL,
    at         TEXT NOT NULL,
    day        TEXT NOT NULL,
    kind       TEXT NOT NULL,
    value_ms   INTEGER,
    detail     TEXT,
    PRIMARY KEY (session_id, at, kind)
);
CREATE INDEX IF NOT EXISTS idx_agent_event_day ON agent_event (day);

CREATE TABLE IF NOT EXISTS ingest_agent_file (
    path        TEXT PRIMARY KEY,
    read_offset INTEGER NOT NULL,
    updated_at  TEXT NOT NULL
);

-- 개선 제안 → 도입(md 반영) → 효과 측정. baseline 은 도입 직전 14일 값을 도입 순간에 고정해 둔다
-- status: proposed | adopted | rejected | done(측정 확정)
CREATE TABLE IF NOT EXISTS agent_insight (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at    TEXT NOT NULL,
    week_key      TEXT NOT NULL,
    source        TEXT NOT NULL,
    title         TEXT NOT NULL,
    problem       TEXT,
    metric        TEXT,
    scope         TEXT,
    target        TEXT,
    snippet       TEXT,
    status        TEXT NOT NULL,
    adopted_at    TEXT,
    applied_path  TEXT,
    baseline      REAL,
    baseline_from TEXT,
    baseline_to   TEXT,
    result        REAL,
    result_from   TEXT,
    result_to     TEXT,
    verdict       TEXT,
    closed_at     TEXT,
    note          TEXT
);

-- ───────── 사용자 설정 ─────────
-- 화면의 설정에서 바꾸는 값(JSON). key = projects | routines | work-text
CREATE TABLE IF NOT EXISTS setting (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
