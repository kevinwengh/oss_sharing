#!/usr/bin/env bash
#
# Starts the POC server from the shaded jar.
#
# Environment:
#   TLS_GROUPS  comma-separated TLS named groups to offer (default: PQC hybrids + secp256r1).
#               Keep at least one group the JDK itself recognises, such as secp256r1: Netty's
#               JdkSslContext initialises a JDK SSLEngine, and JDK 25 does not know the ML-KEM
#               hybrids, so an ML-KEM-only list aborts start-up.
#   TLS_DEBUG   set to 1 to enable BCJSSE and JSSE handshake tracing (very verbose)
#   CONFIG      path to the YAML config (default: ./config.yml)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

JAR="target/netty-pqc-poc-1.0.0-SNAPSHOT.jar"
CONFIG="${CONFIG:-./config.yml}"
TLS_GROUPS="${TLS_GROUPS:-SecP256r1MLKEM768,X25519MLKEM768,secp256r1}"

if [[ ! -f "$JAR" ]]; then
  echo "ERROR: $JAR not found. Build it first:" >&2
  echo "  mvn clean package" >&2
  exit 1
fi

java_opts=(
  "-Djdk.tls.namedGroups=${TLS_GROUPS}"
)

if [[ "${TLS_DEBUG:-0}" == "1" ]]; then
  java_opts+=(
    "-Dorg.bouncycastle.jsse.server.enableDebug=true"
    "-Djavax.net.debug=ssl,handshake"
  )
fi

echo "TLS groups: ${TLS_GROUPS}"
exec java "${java_opts[@]}" -jar "$JAR" "$CONFIG"
