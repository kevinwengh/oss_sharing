#!/bin/bash

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "Local OpenSSL implementation:"
openssl version
echo
echo "NOTE: Apple LibreSSL does NOT support PQC hybrid groups like SecP256r1MLKEM768/X25519MLKEM768."
echo "If you see 'Failed to set groups', the client is the limitation, not necessarily the server."
echo "Use a PQC-capable client (e.g. BCJSSE Java client or OpenSSL+OQS build)."
echo

HOST=localhost:8443
CA_FILE="${CA_FILE:-$SCRIPT_DIR/server.crt}"
TLS_GROUPS=("X25519MLKEM768" "SecP256r1MLKEM768" "secp256r1")

if [ ! -f "$CA_FILE" ]; then
  echo "ERROR: CA file not found: $CA_FILE"
  exit 1
fi

echo "Target: ${HOST}"
echo "CA file: ${CA_FILE}"
echo "Groups to test: ${TLS_GROUPS[*]}"
echo

for group in "${TLS_GROUPS[@]}"; do
  echo "===== Testing group: $group ====="
  cmd="openssl s_client -connect $HOST -tls1_3 -groups $group -CAfile $CA_FILE -msg -state"
  echo "Executing: $cmd"

  output=$(eval "$cmd" </dev/null 2>&1)
  status=$?

  if [ $status -eq 0 ] && echo "$output" | grep -q "New, TLSv1.3, Cipher is"; then
    echo "PASS: handshake succeeded for group $group"
  else
    echo "FAIL: handshake failed for group $group"
    echo "--- openssl output (first 40 lines) ---"
    echo "$output" | head -n 100
    echo "--- end openssl output ---"
  fi
  echo
done