package com.kevinwen.pqc.netty;

import java.time.Instant;

/**
 * JSON payload of {@code /crypto/status}. Record component order defines field order in the
 * response.
 *
 * @param serviceName name from the application config, so a response can be traced to an instance
 * @param provider JCA provider that produced {@code sampleHashPrefix}
 * @param algorithm digest algorithm used
 * @param sampleHashPrefix first bytes of a digest over a fixed input, proving the provider works
 * @param timestamp ISO-8601 instant the response was produced
 */
public record CryptoStatus(
    String serviceName,
    String provider,
    String algorithm,
    String sampleHashPrefix,
    String timestamp) {

  static CryptoStatus of(
      String serviceName, String provider, String algorithm, String sampleHashPrefix) {
    return new CryptoStatus(
        serviceName, provider, algorithm, sampleHashPrefix, Instant.now().toString());
  }
}
