#!/usr/bin/env bash
# 프론트(React) 빌드 → 스프링 jar 에 static 으로 묶기. 결과: back/build/libs/worklog-back-*.jar
set -euo pipefail
cd "$(dirname "$0")"

(cd front && npm install --no-audit --no-fund --silent && npm run build)
(cd back && ./gradlew bootJar -q)
ls -1 back/build/libs/worklog-back-*.jar
