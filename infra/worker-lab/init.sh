#!/bin/sh
set -eu
for migration in $(find /migrations -name 'V*.sql' | sort -V); do
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f "$migration"
done
