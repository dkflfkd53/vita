#!/usr/bin/env bash
# DB 를 백업으로 되돌린다. 앱 없이도 동작한다(다른 맥에서 복구할 때도 이것만 있으면 됨).
#   ./restore.sh            → 백업 목록(맥 안 + iCloud)을 보여 주고 번호로 고름
#   ./restore.sh <파일>     → 그 파일로 되돌림 (.db.gz 또는 암호화된 .db.gz.enc)
# 되돌리기 전 현재 DB 는 worklog.db.before-restore-<시각> 으로 남겨 둔다.
set -euo pipefail

LABEL=com.worklog.server
DATA_DIR="${WORKLOG_DATA_DIR:-$HOME/.claude-worklog}"
LOCAL="$DATA_DIR/backups"
MIRROR="$HOME/Library/Mobile Documents/com~apple~CloudDocs/Worklog-backup"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"

file="${1:-}"
if [ -z "$file" ]; then
  files=()
  while IFS= read -r f; do files+=("$f"); done < <(ls -1t "$LOCAL"/worklog-*.db.gz "$MIRROR"/worklog-*.db.gz.enc 2>/dev/null || true)
  if [ ${#files[@]} -eq 0 ]; then
    echo "백업이 없음: $LOCAL , $MIRROR" >&2
    exit 1
  fi
  for i in "${!files[@]}"; do
    case "${files[$i]}" in *.enc) where="iCloud(암호화)" ;; *) where="맥 안" ;; esac
    printf '%3d) %s  [%s]\n' "$((i + 1))" "$(basename "${files[$i]}")" "$where"
  done
  read -r -p "되돌릴 번호: " n
  file="${files[$((n - 1))]}"
fi
[ -f "$file" ] || { echo "파일 없음: $file" >&2; exit 1; }

read -r -p "'$(basename "$file")' 로 되돌릴까요? 지금 DB 는 따로 보관됩니다 [y/N] " yes
[ "$yes" = "y" ] || [ "$yes" = "Y" ] || { echo "취소"; exit 0; }

mkdir -p "$DATA_DIR"
tmp="$DATA_DIR/.restore.db"
trap 'rm -f "$tmp"' EXIT   # 암호가 틀리거나 파일이 깨져 중간에 멈춰도 찌꺼기를 남기지 않는다
case "$file" in
  *.enc)
    pw="${WORKLOG_BACKUP_PW:-$(security find-generic-password -s worklog-backup -a worklog -w 2>/dev/null || true)}"
    if [ -z "$pw" ]; then read -r -s -p "백업 암호: " pw; echo; fi
    WORKLOG_BACKUP_PW="$pw" /usr/bin/openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:WORKLOG_BACKUP_PW \
      -in "$file" | gunzip > "$tmp"
    ;;
  *.gz) gunzip -c "$file" > "$tmp" ;;
  *) echo ".db.gz 또는 .db.gz.enc 파일만 됨" >&2; exit 1 ;;
esac
check="$(sqlite3 "$tmp" 'PRAGMA integrity_check;' 2>&1 || true)"
[ "$check" = "ok" ] || { rm -f "$tmp"; echo "백업 파일 검사 실패: $check" >&2; exit 1; }

# 앱을 멈추고 바꿔 끼운다
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
for _ in $(seq 1 30); do launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || break; sleep 0.5; done

ts="$(date +%Y%m%d-%H%M%S)"
if [ -f "$DATA_DIR/worklog.db" ]; then
  cp "$DATA_DIR/worklog.db" "$DATA_DIR/worklog.db.before-restore-$ts"
  [ -f "$DATA_DIR/worklog.db-wal" ] && cp "$DATA_DIR/worklog.db-wal" "$DATA_DIR/worklog.db.before-restore-$ts-wal"
fi
mv "$tmp" "$DATA_DIR/worklog.db"
rm -f "$DATA_DIR/worklog.db-wal" "$DATA_DIR/worklog.db-shm"
echo "되돌림 완료. 이전 DB: $DATA_DIR/worklog.db.before-restore-$ts"

if [ -f "$PLIST" ]; then
  launchctl bootstrap "gui/$(id -u)" "$PLIST" && echo "앱 다시 시작 — 백업 이후의 세션 기록은 다음 수집 때 자동으로 다시 채워짐(원본이 남아 있는 30일 안)"
else
  echo "launchd 등록이 없음 — ./install.sh 로 앱을 설치하세요"
fi
