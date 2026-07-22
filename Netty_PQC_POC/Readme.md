# Netty PQC POC

A small HTTPS server that terminates TLS 1.3 through the **Bouncy Castle JSSE provider** instead of
the JDK's own TLS stack, so the handshake can use NIST-standardised **post-quantum hybrid key
exchange** — `X25519MLKEM768` and `SecP256r1MLKEM768`.

The two HTTP endpoints exist only to report what the JVM actually negotiated; the interesting part
is the handshake itself.

> **Write-up:** [Your Post-Quantum TLS Might Not Be Post-Quantum](docs/verifying-post-quantum-tls.md)
> — how this POC reported ML-KEM as enabled while negotiating classical ECDHE, and the verification
> ladder that catches it.

## How it works

| Piece | Role |
| --- | --- |
| `NettyPqcApplication` | Loads config, installs providers, binds the HTTPS listener |
| `PqcTlsSupport` | Registers BC/BCJSSE and builds the Netty `SslContext` against them |
| `PqcStatusHandler` | Serves the two read-only diagnostic endpoints |
| `AppConfig` | YAML config with defaults and `-D` overrides |

Three things make PQC groups reachable, and all three are required:

1. `SslProvider.JDK` combined with `sslContextProvider(BCJSSE)` — the JDK provider is the only Netty
   SSL provider that delegates to a JCA `Provider`, so it is what routes the handshake into Bouncy
   Castle.
2. `-Djdk.tls.namedGroups=SecP256r1MLKEM768,X25519MLKEM768,secp256r1` at JVM start-up, which selects
   the groups offered. `run.sh` sets this.
3. **BCJSSE must be pinned to the BC provider** — `new BouncyCastleJsseProvider(bouncyCastle)`, not
   the no-arg constructor.

Point 3 is the subtle one. The no-arg `BouncyCastleJsseProvider()` resolves JCA services across all
installed providers, so on JDK 24+ — which ships its own ML-KEM — `KeyPairGenerator.getInstance(
"ML-KEM")` returns SunJCE, which then rejects Bouncy Castle's `MLKEMParameterSpec`. Bouncy Castle
reads that as "algorithm unsupported" and disables *every* ML-KEM named group. The handshake quietly
falls back to classical ECDHE, and the only evidence is a log line:

```text
WARNING: 'jdk.tls.namedGroups' contains disabled NamedGroup: X25519MLKEM768
```

`PqcHandshakeTest` guards this in the build by driving a real handshake whose client offers the
hybrid group and nothing else — with the pinning removed it fails with `handshake_failure(40)`
rather than quietly downgrading. A capability probe is not enough here: asking Bouncy Castle whether
it can generate ML-KEM keys returns yes even in the broken state, because the failure is in which
provider BCJSSE's internal helper resolves, not in BC itself.

### One constraint on `jdk.tls.namedGroups`

The value must contain at least one group the **JDK's own** JSSE recognises (`secp256r1` here). Netty's
`JdkSslContext` static initializer builds a JDK `SSLEngine` to compute defaults, and JDK 25's JSSE
does not know the ML-KEM hybrid groups, so an ML-KEM-only value aborts start-up with:

```text
java.lang.IllegalArgumentException: System property jdk.tls.namedGroups(X25519MLKEM768) contains no
supported named groups
```

## Requirements

- JDK 25
- Maven 3.9.5+
- For `verify.sh`: OpenSSL 3.5+ (Apple's bundled LibreSSL cannot negotiate ML-KEM groups)

## Build

```shell
mvn clean package
```

Produces the runnable uber jar `target/netty-pqc-poc-1.0.0-SNAPSHOT.jar`.

## Run

```shell
./run.sh
```

`run.sh` honours a few environment variables:

| Variable | Default | Meaning |
| --- | --- | --- |
| `TLS_GROUPS` | `SecP256r1MLKEM768,X25519MLKEM768,secp256r1` | Named groups offered in the handshake (must keep one JDK-recognised group) |
| `TLS_DEBUG` | `0` | `1` enables BCJSSE + JSSE handshake tracing |
| `CONFIG` | `./config.yml` | Config file path |

## Endpoints

```shell
curl -k https://localhost:8443/crypto/pqc
```

```text
TLS Provider: BCJSSE 1.0023
Provider Class: org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
...
Configured TLS Groups (requested via -Djdk.tls.namedGroups):
SecP256r1MLKEM768,X25519MLKEM768,secp256r1

ML-KEM Hybrid Requested: YES
ML-KEM Key Generation Available: YES
```

```shell
curl -k https://localhost:8443/crypto/status
```

```json
{
  "serviceName" : "netty-pqc-service",
  "provider" : "BC",
  "algorithm" : "SHA3-256",
  "sampleHashPrefix" : "6f2a1c04",
  "timestamp" : "2026-07-21T22:00:00.000Z"
}
```

Note that `/crypto/pqc` reports which groups were *requested* and whether the provider can generate
ML-KEM keys — not which group a given client actually negotiated. Use `verify.sh` for that.

## Verify the handshake

With the server running:

```shell
./verify.sh
```

It attempts one TLS 1.3 handshake per group and exits non-zero if any of them fails:

```text
PASS: handshake succeeded for group X25519MLKEM768
PASS: handshake succeeded for group SecP256r1MLKEM768
PASS: handshake succeeded for group secp256r1
All 3 groups negotiated successfully.
```

To see the negotiated group directly:

```shell
openssl s_client -connect localhost:8443 -tls1_3 -groups X25519MLKEM768 -CAfile server.crt \
  </dev/null 2>&1 | grep "Negotiated TLS1.3 group"
# Negotiated TLS1.3 group: X25519MLKEM768
```

## Configuration

`config.yml` is fully optional — every key falls back to a default. Values can be overridden at
start-up:

| System property | Config key | Default |
| --- | --- | --- |
| `-Dhttps.port` | `server.httpsPort` | `8443` |
| `-Dkeystore.path` | `server.keyStorePath` | `server.p12` |
| `-Dkeystore.password` | `server.keyStorePassword` | `changeit` |
| `-Dkeystore.type` | `server.keyStoreType` | `PKCS12` |

## Key material

`server.p12` and `server.crt` are **throwaway self-signed demo artifacts** committed so the POC runs
out of the box against `localhost`. They are not secret and must not be reused anywhere else.
Regenerate them with:

```shell
./generate-cert.sh
```

## Test

```shell
mvn test
```

- `PqcHandshakeTest` binds the real server on an ephemeral port and completes a TLS 1.3 handshake
  with a BCJSSE client restricted to `X25519MLKEM768`. It is the build's proof that the PQC key
  exchange actually happens.
- The handler tests drive a Netty `EmbeddedChannel`, exercising the real pipeline without binding a
  socket or performing a handshake.

`verify.sh` covers the same ground from outside the JVM with an independent OpenSSL client.
