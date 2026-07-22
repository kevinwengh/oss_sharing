package com.kevinwen.pqc.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.GeneralSecurityException;
import java.security.Security;
import javax.net.ssl.SSLContext;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.jupiter.api.Test;

class PqcTlsSupportTest {

  @Test
  void registerProvidersIsIdempotent() {
    PqcTlsSupport.registerProviders();
    PqcTlsSupport.registerProviders();

    assertNotNull(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME));
    assertNotNull(Security.getProvider(BouncyCastleJsseProvider.PROVIDER_NAME));
  }

  /**
   * Checks the underlying primitive is present. This is a necessary but <em>not</em> sufficient
   * condition for a PQC handshake: it asks BC directly, so it passes even when BCJSSE has disabled
   * every ML-KEM group. {@link PqcHandshakeTest} is what guards that.
   */
  @Test
  void mlKemKeyGenerationIsAvailableThroughBouncyCastle() {
    PqcTlsSupport.registerProviders();

    assertTrue(PqcTlsSupport.isMlKemAvailable(), "Bouncy Castle cannot generate ML-KEM key pairs");
  }

  @Test
  void defaultSslContextUsesBouncyCastleJsse() throws GeneralSecurityException {
    PqcTlsSupport.registerProviders();
    PqcTlsSupport.installAsDefaultSslContext();

    assertEquals(
        BouncyCastleJsseProvider.PROVIDER_NAME, SSLContext.getDefault().getProvider().getName());
  }
}
