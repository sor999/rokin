#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKER_DIR="$ROOT_DIR/services/worker-go"
IMAGE_NAME="${IMAGE_NAME:-robot-worker}"

docker build -t "$IMAGE_NAME" "$WORKER_DIR"

echo "Built Docker image: $IMAGE_NAME"
