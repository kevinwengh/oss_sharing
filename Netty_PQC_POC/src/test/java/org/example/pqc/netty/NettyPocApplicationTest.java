/*
 * Personal property of Kevin Wen (Kevinwen@gmail.com).
 * A written permission is required for distribution and re-use of any code from this repo
 */
package org.example.pqc.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.List;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NettyPocApplicationTest {

  @TempDir Path tempDirectory;

  @Test
  void loadConfigAppliesDefaultsForMissingServerSettings() throws Exception {
    Path config = tempDirectory.resolve("config.yml");
    Files.writeString(config, "serviceName: test-pqc\nserver: {}\n");

    NettyPocApplication.AppConfig loaded = NettyPocApplication.loadConfig(config.toString());

    assertEquals("test-pqc", loaded.serviceName);
    assertEquals(8443, loaded.server.httpsPort);
    assertEquals(List.of("TLSv1.3"), loaded.server.supportedProtocols);
  }

  @Test
  void loadConfigPreservesExplicitServerSettings() throws Exception {
    Path config = tempDirectory.resolve("custom.yml");
    Files.writeString(
        config,
        "serviceName: custom\nserver:\n  httpsPort: 9443\n  supportedProtocols: [TLSv1.2, TLSv1.3]\n");

    NettyPocApplication.AppConfig loaded = NettyPocApplication.loadConfig(config.toString());

    assertEquals(9443, loaded.server.httpsPort);
    assertEquals(List.of("TLSv1.2", "TLSv1.3"), loaded.server.supportedProtocols);
  }

  @Test
  void registerProvidersIsIdempotent() {
    NettyPocApplication.registerProviders();
    NettyPocApplication.registerProviders();

    assertNotNull(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME));
    assertNotNull(Security.getProvider(BouncyCastleJsseProvider.PROVIDER_NAME));
  }
}
