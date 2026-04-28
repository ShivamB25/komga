#!/bin/sh
set -eu

if [ "${1:-}" = "migration" ]; then
  shift
  exec java \
    -Dspring.profiles.include=docker \
    --enable-native-access=ALL-UNNAMED \
    -cp "application.jar:lib/*" \
    org.gotson.komga.infrastructure.migration.MigrationCommandKt \
    "$@"
fi

exec java \
  -Dspring.profiles.include=docker \
  --enable-native-access=ALL-UNNAMED \
  -jar application.jar \
  --spring.config.additional-location=file:/config/ \
  "$@"
