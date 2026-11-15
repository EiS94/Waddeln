#!/usr/bin/env bash
# Builds the server and (re)starts it in Docker on a remote host, e.g. a Raspberry Pi.
# Usage: server/deploy.sh
# Settings (environment variables, or put them into server/deploy.env, which is not committed):
#   DEPLOY_HOST  SSH host of the server              (default: raspberry)
#   DEPLOY_DIR   directory for the files on the host (default: ~/waddeln-server)
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f server/deploy.env ]; then source server/deploy.env; fi
HOST=${DEPLOY_HOST:-raspberry}
REMOTE_DIR=${DEPLOY_DIR:-'~/waddeln-server'}

# Two Gradle runs: the Wasm builds of :webApp share an output directory with other tasks.
./gradlew :server:installDist --console=plain -q
./gradlew :webApp:wasmJsBrowserDistribution --console=plain -q
# The browser version without source maps.
rm -rf server/build/web
mkdir -p server/build/web
(cd webApp/build/dist/wasmJs/productionExecutable && tar --exclude='*.map' -cf - .) | tar -C server/build/web -xf -
ssh "$HOST" "mkdir -p $REMOTE_DIR && rm -rf $REMOTE_DIR/install $REMOTE_DIR/web"
tar -C server -cf - Dockerfile docker-compose.yml -C build install web | ssh "$HOST" "tar -C $REMOTE_DIR -xf -"
ssh "$HOST" "cd $REMOTE_DIR && docker compose up -d --build && sleep 4 && curl -fsS http://localhost:8765/health && echo ' - server is up' && curl -fsS -o /dev/null http://localhost:8765/play/ && echo 'web app is served at /play/'"
