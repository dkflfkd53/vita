#!/usr/bin/env bash
# iCloud 암호화 백업의 암호를 보여 준다. 맥을 잃으면 이 암호 없이는 복구할 수 없으니 비밀번호 관리자 등에 따로 보관할 것.
security find-generic-password -s worklog-backup -a worklog -w 2>/dev/null \
  || { echo "아직 암호가 없음 — 앱이 첫 백업을 하면 만들어짐" >&2; exit 1; }
