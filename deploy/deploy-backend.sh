#!/usr/bin/env bash
# =============================================================================
# NAVIX backend deploy script — runs ON THE EC2 INSTANCE.
#
# It builds the backend Docker image from the repo and (re)starts the container
# with your production env file. Idempotent: re-run it any time to redeploy.
#
# Prereqs on the EC2 box:
#   - Docker installed and running  (see DEPLOYMENT.md "Install Docker on EC2")
#   - This repo cloned/copied here, and you run this from the REPO ROOT
#   - A filled-in  deploy/backend.env  (copy from deploy/backend.env.example)
#
# Usage (from repo root on EC2):
#   bash deploy/deploy-backend.sh
# =============================================================================
set -euo pipefail

IMAGE_NAME="navix-backend:latest"
CONTAINER_NAME="navix-backend"
ENV_FILE="deploy/backend.env"
PORT="8080"

# --- sanity checks ------------------------------------------------------------
if [[ ! -f "Dockerfile.backend" ]]; then
  echo "ERROR: run this from the repo root (Dockerfile.backend not found here)." >&2
  exit 1
fi
if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: $ENV_FILE not found." >&2
  echo "       cp deploy/backend.env.example deploy/backend.env  and fill it in." >&2
  exit 1
fi

echo "==> Building image $IMAGE_NAME (this can take a few minutes the first time)..."
docker build -f Dockerfile.backend -t "$IMAGE_NAME" .

echo "==> Stopping any existing container..."
docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

echo "==> Starting new container..."
docker run -d \
  --name "$CONTAINER_NAME" \
  --restart unless-stopped \
  --env-file "$ENV_FILE" \
  -p "${PORT}:8080" \
  "$IMAGE_NAME"

echo "==> Waiting for health (up to ~90s)..."
for i in $(seq 1 30); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER_NAME" 2>/dev/null || echo starting)"
  if [[ "$status" == "healthy" ]]; then
    echo "==> Backend is HEALTHY on port ${PORT}."
    echo "    Test:  curl http://localhost:${PORT}/actuator/health"
    exit 0
  fi
  sleep 3
done

echo "WARNING: container did not report healthy in time. Recent logs:" >&2
docker logs --tail 50 "$CONTAINER_NAME" >&2
echo "If you see a DB connection error, check DB_URL / security group rules (RDS must allow 5432 from this EC2)." >&2
exit 1
