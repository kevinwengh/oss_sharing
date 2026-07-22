#!/usr/bin/env bash
#
# Attempts a TLS 1.3 handshake against the running server once per named group and reports which
# groups negotiate successfully.
#
# Environment:
#   HOST     host:port to connect to (default: localhost:8443)
#   CA_FILE  certificate to trust (default: ./server.crt)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

HOST="${HOST:-localhost:8443}"
CA_FILE="${CA_FILE:-$SCRIPT_DIR/server.crt}"
TLS_GROUPS=(X25519MLKEM768 SecP256r1MLKEM768 secp256r1)
OUTPUT_LINES=40

if [[ ! -f "$CA_FILE" ]]; then
  echo "ERROR: CA file not found: $CA_FILE" >&2
  exit 1
fi

echo "Local OpenSSL implementation:"
openssl version
echo
echo "NOTE: Apple LibreSSL does NOT support the PQC hybrid groups (SecP256r1MLKEM768 /"
echo "X25519MLKEM768). A 'Failed to set groups' error means the client is the limitation,"
echo "not the server. Use a PQC-capable client such as OpenSSL 3.5+ or a BCJSSE Java client."
echo
echo "Target:         $HOST"
echo "CA file:        $CA_FILE"
echo "Groups to test: ${TLS_GROUPS[*]}"
echo

failures=0
for group in "${TLS_GROUPS[@]}"; do
  echo "===== Testing group: $group ====="

  output=$(openssl s_client \
    -connect "$HOST" \
    -servername "${HOST%%:*}" \
    -tls1_3 \
    -groups "$group" \
    -CAfile "$CA_FILE" \
    -state </dev/null 2>&1)
  status=$?

  if [[ $status -eq 0 ]] && grep -q "New, TLSv1.3, Cipher is" <<<"$output"; then
    echo "PASS: handshake succeeded for group $group"
  else
    echo "FAIL: handshake failed for group $group"
    echo "--- openssl output (first $OUTPUT_LINES lines) ---"
    head -n "$OUTPUT_LINES" <<<"$output"
    echo "--- end openssl output ---"
    ((failures++))
  fi
  echo
done

if ((failures > 0)); then
  echo "$failures of ${#TLS_GROUPS[@]} group(s) failed."
  exit 1
fi

echo "All ${#TLS_GROUPS[@]} groups negotiated successfully."
