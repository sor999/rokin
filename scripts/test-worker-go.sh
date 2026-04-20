#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKER_DIR="$ROOT_DIR/services/worker-go"

docker run --rm \
  -v "$WORKER_DIR:/app" \
  -w /app \
  golang:1.22-bookworm \
  bash -lc 'apt-get update >/dev/null && apt-get install -y --no-install-recommends librdkafka-dev >/dev/null && go test ./...'
