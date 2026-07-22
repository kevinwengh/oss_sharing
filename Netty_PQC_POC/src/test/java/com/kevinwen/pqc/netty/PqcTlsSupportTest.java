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
   * Guards the reason BCJSSE is pinned to the BC provider: without it, {@code
   * KeyPairGenerator.getInstance("ML-KEM")} resolves to the JDK's own implementation, Bouncy Castle
   * quietly disables every ML-KEM named group, and the handshake silently drops back to classical
   * key exchange.
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
