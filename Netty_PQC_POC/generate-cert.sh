#!/usr/bin/env bash
#
# Regenerates the throwaway self-signed key material used by this POC.
#
# The server.p12 / server.crt committed to this repo are demo-only artifacts for a localhost
# handshake. Never reuse them, or this script's default password, for anything real.
#
# Environment:
#   KEYSTORE_PASSWORD  key store password (default: changeit)
#   VALIDITY_DAYS      certificate lifetime (default: 3650)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-changeit}"
VALIDITY_DAYS="${VALIDITY_DAYS:-3650}"
KEYSTORE="server.p12"
CERT="server.crt"
ALIAS="server"

rm -f "$KEYSTORE" "$CERT"

keytool -genkeypair \
  -alias "$ALIAS" \
  -keyalg EC \
  -groupname secp256r1 \
  -sigalg SHA256withECDSA \
  -keystore "$KEYSTORE" \
  -storetype PKCS12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -validity "$VALIDITY_DAYS" \
  -dname "CN=localhost, OU=Netty PQC POC, O=Netty PQC POC, L=Seattle, ST=WA, C=US" \
  -ext "SAN=DNS:localhost,IP:127.0.0.1"

keytool -exportcert \
  -alias "$ALIAS" \
  -keystore "$KEYSTORE" \
  -storepass "$KEYSTORE_PASSWORD" \
  -rfc \
  -file "$CERT"

echo
echo "Wrote $KEYSTORE and $CERT:"
keytool -list -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" | tail -n 3
