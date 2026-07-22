#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

java \
 -Djdk.tls.namedGroups=SecP256r1MLKEM768,X25519MLKEM768,secp256r1 \
 -Dorg.bouncycastle.jsse.server.enableDebug=true \
 -Djavax.net.debug=ssl,handshake \
 -jar ./target/Netty_Poc-1.0-SNAPSHOT.jar ./config.yml