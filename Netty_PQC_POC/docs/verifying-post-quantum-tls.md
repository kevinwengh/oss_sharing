---
title: Your Post-Quantum TLS Might Not Be Post-Quantum
description: A Netty and Bouncy Castle proof-of-concept reported ML-KEM as enabled while negotiating classical ECDHE, and what it takes to verify a PQC handshake rather than trust it.
summary: How a one-line provider mistake silently downgraded a post-quantum TLS handshake, and the verification ladder that catches it.
date: 2026-07-21
tags: [post-quantum-cryptography, tls, java, testing]
---

I had a small proof-of-concept: a Netty HTTPS server terminating TLS 1.3 through the Bouncy Castle
JSSE provider, so the handshake could use NIST-standardised post-quantum hybrid key exchange —
`X25519MLKEM768` and `SecP256r1MLKEM768`. It had a status endpoint that reported the crypto stack.
The endpoint said:

```text
ML-KEM Hybrid Enabled: YES
```

It was not. Every handshake was classical ECDHE. The POC had never once performed a post-quantum key
exchange, and nothing in it was capable of noticing.

The bug is worth a look because the mechanism is general and the failure is silent by construction.
The verification technique is worth more.

## The endpoint that could only ever say yes

Here is the original check, reduced to its essentials:

```java
String groups = System.getProperty("jdk.tls.namedGroups", "default");
boolean mlkemEnabled = groups.contains("MLKEM") || groups.contains("mlkem");
```

The server was started by a script that set that very property. So the endpoint asked "did I ask for
ML-KEM?", got back "yes, you did", and reported it as "ML-KEM Hybrid Enabled". It was a mirror
pointed at the launch command. No arrangement of the TLS stack could have made it print `NO`.

This is the first and most common form of the mistake: **reporting configuration as if it were
outcome.** Requesting a named group and negotiating one are different events, separated by an entire
provider stack that is free to quietly discard your request.

## What was actually discarding it

Buried in the start-up log, among Bouncy Castle's routine chatter about disabled algorithms:

```text
WARNING: 'jdk.tls.namedGroups' contains disabled NamedGroup: SecP256r1MLKEM768
WARNING: 'jdk.tls.namedGroups' contains disabled NamedGroup: X25519MLKEM768
```

*Disabled*, not *unrecognised*. Bouncy Castle knew exactly what those groups were and had decided it
could not do them.

The cause is a collision between two reasonable behaviours. BCJSSE was registered the obvious way:

```java
Security.addProvider(new BouncyCastleProvider());
Security.addProvider(new BouncyCastleJsseProvider());   // the problem
```

The no-arg `BouncyCastleJsseProvider()` builds its internal JCA helper against the *default* provider
search order — every installed provider, in priority order. Meanwhile, when Bouncy Castle decides
whether an ML-KEM group is usable, it runs a support probe that boils down to this (from
`KemUtil` in `bctls`):

```java
KeyPairGenerator keyPairGenerator = crypto.getHelper().createKeyPairGenerator("ML-KEM");
keyPairGenerator.initialize(MLKEMParameterSpec.fromName(kemName), crypto.getSecureRandom());
```

Modern JDKs ship their own ML-KEM implementation — it arrived in JDK 24, and on the JDK 25 used here
`KeyPairGenerator.getInstance("ML-KEM")` resolves to **SunJCE**. SunJCE is then handed
`org.bouncycastle.jcajce.spec.MLKEMParameterSpec` — a parameter spec it has never heard of. It throws.
The probe catches the exception, concludes "ML-KEM unsupported", and every ML-KEM named group is
struck off the list. TLS 1.3 then negotiates the best remaining group, which is classical, and the
handshake succeeds. Nothing fails. Nothing is logged at error level.

Two components each did something defensible. The result was a silent security downgrade.

The fix is one argument — pin BCJSSE's crypto to the Bouncy Castle provider instance, so the helper
never consults SunJCE:

```java
Provider bouncyCastle = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
if (bouncyCastle == null) {
  bouncyCastle = new BouncyCastleProvider();
  Security.addProvider(bouncyCastle);
}
Security.addProvider(new BouncyCastleJsseProvider(bouncyCastle));
```

A one-line fix for a bug that invalidated the entire point of the project. That asymmetry is exactly
why the verification matters more than the fix.

## The verification ladder

Rank the ways you might convince yourself a PQC handshake is happening, weakest first.

### Level 0 — echo the configuration

`System.getProperty("jdk.tls.namedGroups").contains("MLKEM")`. This is what the POC did. It proves
you typed something on the command line. Discard it.

### Level 1 — probe the primitive

Ask the library whether it can do the thing:

```java
KeyPairGenerator generator = KeyPairGenerator.getInstance("ML-KEM", "BC");
generator.initialize(MLKEMParameterSpec.ml_kem_768, new SecureRandom());
```

Better — this is a live capability check, not a string match. It is also **necessary but not
sufficient**, and I want to be precise about why, because I initially over-credited it.

When I re-ran my test suite against the *broken*, un-pinned provider, this check still passed. Of
course it did: it names `"BC"` explicitly, and Bouncy Castle's ML-KEM was never the problem. The
defect was in which provider *BCJSSE's internal helper* resolved. A probe that reaches around the
faulty component cannot detect a fault in it.

Any check that queries a component directly will miss defects in how that component is wired to
others. That is a general property of capability probes, not a quirk of this library.

### Level 2 — negotiate with an external client

Make something outside your JVM complete a handshake, and restrict it so that only the answer you
want to prove is acceptable:

```shell
openssl s_client -connect localhost:8443 -tls1_3 -groups X25519MLKEM768 -CAfile server.crt
```

`-groups X25519MLKEM768` makes the client offer that group and nothing else. TLS 1.3 cannot complete
without a mutually supported group, so success is proof by exclusion — the server chose the hybrid,
because there was nothing else to choose. OpenSSL then states the result outright:

```text
Negotiated TLS1.3 group: X25519MLKEM768
New, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
```

This is real evidence. Two independent implementations, an actual key exchange, an explicit
statement of the group used. Note that a client which offers *several* groups proves much less: a
successful handshake then tells you only that one of them worked.

You need a genuinely PQC-capable client. OpenSSL 3.5+ works — the run above is 3.6.3. The LibreSSL
that ships as `/usr/bin/openssl` on macOS does not:

```text
$ /usr/bin/openssl version
LibreSSL 3.3.6
$ /usr/bin/openssl s_client -connect localhost:8443 -tls1_3 -groups X25519MLKEM768 ...
Failed to set groups 'X25519MLKEM768'
```

That is the client refusing to *offer* the group, before a single byte reaches the server. It looks
like a server problem and is not. Say so loudly in whatever script you write, or you will spend an
afternoon debugging the wrong machine.

### Level 3 — make the build prove it

An external script proves the system worked on the day you ran it. It does not stop the next commit
from breaking it. The failure mode here is silent, so a regression would not announce itself.

The same proof-by-exclusion works in-process. Bouncy Castle exposes per-connection named groups
through `BCSSLParameters`, which is the programmatic equivalent of `openssl -groups`:

```java
BCSSLSocket bcSocket = (BCSSLSocket) socket;
BCSSLParameters parameters = bcSocket.getParameters();
parameters.setNamedGroups(new String[] {"X25519MLKEM768"});
bcSocket.setParameters(parameters);

socket.startHandshake();
```

Bind the real server on an ephemeral port, point that client at it, and assert the response. The
client offers one group, so a completed handshake means ML-KEM ran. The test is fast — about two
seconds — and it fails loudly on exactly the defect that used to be invisible.

## The step almost everyone skips: the negative control

A passing test that would also pass in the broken state is worse than no test, because it converts
an open question into false confidence.

So verify the verifier. I reintroduced the original bug — swapped the pinned provider back for
`new BouncyCastleJsseProvider()` — and re-ran:

```text
PqcTlsSupportTest      Tests run: 3, Failures: 0, Errors: 0   <- passed anyway
PqcHandshakeTest       org.bouncycastle.tls.TlsFatalAlertReceived: handshake_failure(40)
```

That single run establishes two things at once. The handshake test genuinely discriminates: it fails,
with the precise alert you would expect, when and only when the defect is present. And the capability
probe genuinely does not: it sailed through the broken build, which is what promoted it from
"regression guard" to "necessary precondition" in my notes and in the code comments.

Without the negative control I would have shipped a test suite that looked green for the wrong
reason, and written a confident summary saying the regression was covered. I had in fact already
written that summary. The negative control is what corrected it.

If you take one habit from this: **after writing a security test, break the security property on
purpose and confirm the test fails.** If it still passes, you have written a test of something else.

## A wrinkle worth knowing about

While building the in-process test I first tried the most direct form of proof by exclusion — set
`jdk.tls.namedGroups=X25519MLKEM768` for the whole test JVM, so nothing classical exists anywhere.
Start-up died:

```text
java.lang.IllegalArgumentException: System property jdk.tls.namedGroups(X25519MLKEM768) contains no
    supported named groups
        at java.base/sun.security.ssl.NamedGroup$SupportedGroups.<clinit>
        at io.netty.handler.ssl.JdkSslContext$Defaults.init
        at io.netty.handler.ssl.JdkSslContext.<clinit>
```

Netty's `JdkSslContext` has a static initializer that builds a **JDK** `SSLEngine` to compute its
defaults. JDK 25's own JSSE ships ML-KEM primitives but does not recognise the hybrid TLS groups, so
a property containing only hybrids leaves it with an empty list and it refuses to initialise — before
your Bouncy Castle context is ever consulted.

The practical rule: `jdk.tls.namedGroups` must retain at least one group the JDK itself recognises
(`secp256r1` will do), even when every handshake you care about is post-quantum. This is why the
in-process test restricts its *client* rather than the JVM. It is also a nasty trap for anyone
hardening a config by deleting the classical entries — the reward for going all-in on PQC is a
start-up crash whose message points at a property you set correctly.

## What this generalises to

The specific bug is a Bouncy Castle and JDK 24+ interaction. The shape of it is not:

- **A cryptographic downgrade is a successful operation.** Nothing throws. Latency does not change.
  Your dashboards stay green. The only signals are a `WARNING` line and the absence of a property you
  were not measuring.
- **Configuration is a request, not a result.** Anything that reports your own configuration back to
  you is instrumentation of your intent, not of the system's behaviour.
- **Adding a provider that overlaps with the platform creates ambiguity.** Both Bouncy Castle and
  modern JDKs implement ML-KEM. Wherever two providers can answer the same question, pin the one you
  mean explicitly rather than relying on search order — and expect the default order to shift under
  you at the next JDK upgrade.
- **Proof by exclusion is the cheapest strong evidence.** You rarely need packet capture. Removing
  every alternative and observing that the operation still succeeds is usually enough, and it fits in
  a unit test.
- **Untested tests are decoration.** Especially for properties whose absence is silent.

## A checklist

If you are standing up post-quantum TLS on the JVM, in order:

1. Log the negotiated group per connection, or expose it. Not the configured groups — the negotiated
   one.
2. Verify from outside with a PQC-capable client restricted to a single hybrid group. Confirm it
   reports that group by name.
3. Pin your JSSE provider's crypto explicitly. Do not rely on JCA search order for an algorithm the
   platform also implements.
4. Keep one JDK-recognised group in `jdk.tls.namedGroups`.
5. Put the handshake in the build as proof by exclusion.
6. Break it on purpose and watch the test fail. Then put it back.

Step 6 is the one that turns the other five into evidence.

---

The POC this article is drawn from is in
[`Netty_PQC_POC`](https://github.com/kevinwengh/oss_sharing/tree/main/Netty_PQC_POC): a Netty server
on JDK 25 with Bouncy Castle 1.83, `PqcHandshakeTest` for the in-build proof, and `verify.sh` for the
external OpenSSL check.
