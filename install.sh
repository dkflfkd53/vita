#!/usr/bin/env bash
# 빌드 후 launchd 사용자 에이전트로 등록 — 로그인하면 자동 시작, 죽으면 다시 띄운다.
# 다시 실행하면 새 jar 로 교체·재시작. 지우려면 ./uninstall.sh (기록 DB 는 남긴다)
set -euo pipefail
cd "$(dirname "$0")"

LABEL=com.worklog.server
DATA_DIR="$HOME/.claude-worklog"
APP_DIR="$DATA_DIR/app"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
JAVA_HOME_21="$(/usr/libexec/java_home -v 21)"
CLAUDE_BIN="$(command -v claude || true)"

./build.sh
mkdir -p "$APP_DIR" "$DATA_DIR/logs"
# 빌드 폴더를 지우거나 다시 빌드해도 돌고 있는 서버가 흔들리지 않게 고정 위치로 복사
cp back/build/libs/worklog-back-*.jar "$APP_DIR/worklog.jar"

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$JAVA_HOME_21/bin/java</string>
    <string>-Xmx512m</string>
    <string>-jar</string>
    <string>$APP_DIR/worklog.jar</string>
    ${CLAUDE_BIN:+<string>--worklog.claude-bin=$CLAUDE_BIN</string>}
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>$(dirname "${CLAUDE_BIN:-/usr/bin/true}"):/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
    <key>WORKLOG_DATA_DIR</key><string>$DATA_DIR</string>
  </dict>
  <key>WorkingDirectory</key><string>$DATA_DIR</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ProcessType</key><string>Standard</string>
  <key>StandardOutPath</key><string>$DATA_DIR/logs/launchd.out</string>
  <key>StandardErrorPath</key><string>$DATA_DIR/logs/launchd.out</string>
</dict>
</plist>
EOF

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
# bootout 은 비동기라 이전 서비스가 완전히 내려가기 전에 bootstrap 하면 "Bootstrap failed: 5" 가 난다
for _ in $(seq 1 30); do
  launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || break
  sleep 0.5
done
for attempt in 1 2 3; do
  launchctl bootstrap "gui/$(id -u)" "$PLIST" && break
  [ "$attempt" = 3 ] && exit 1
  sleep 2
done

for _ in $(seq 1 40); do
  if curl -sf -o /dev/null http://127.0.0.1:4100/api/jobs; then
    echo "실행 중: http://127.0.0.1:4100"
    exit 0
  fi
  sleep 1
done
echo "서버가 40초 안에 뜨지 않음 — $DATA_DIR/logs/launchd.out 확인" >&2
exit 1
