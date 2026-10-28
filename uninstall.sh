#!/usr/bin/env bash
# launchd 등록만 내린다. 쌓인 기록(~/.claude-worklog/worklog.db)은 지우지 않는다
set -euo pipefail
LABEL=com.worklog.server
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
echo "중지·등록 해제 완료. 기록은 ~/.claude-worklog 에 남아 있음"
