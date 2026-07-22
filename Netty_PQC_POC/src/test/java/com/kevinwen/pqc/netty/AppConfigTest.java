package com.kevinwen.pqc.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kevinwen.pqc.netty.AppConfig.ServerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppConfigTest {

  @TempDir Path tempDirectory;

  @Test
  void appliesDefaultsForEmptyServerBlock() throws IOException {
    AppConfig config = load("config.yml", "serviceName: test-pqc\nserver: {}\n");

    assertEquals("test-pqc", config.serviceName());
    assertEquals(8443, config.server().httpsPort());
    assertEquals("server.p12", config.server().keyStorePath());
    assertEquals("PKCS12", config.server().keyStoreType());
    assertEquals(List.of("TLSv1.3"), config.server().supportedProtocols());
  }

  @Test
  void appliesDefaultsWhenServerBlockIsAbsent() throws IOException {
    AppConfig config = load("minimal.yml", "serviceName: minimal\n");

    assertEquals(8443, config.server().httpsPort());
    assertEquals(List.of("TLSv1.3"), config.server().supportedProtocols());
  }

  @Test
  void appliesDefaultServiceNameForEmptyDocument() throws IOException {
    AppConfig config = load("empty.yml", "{}\n");

    assertEquals("netty-pqc-service", config.serviceName());
  }

  @Test
  void preservesExplicitServerSettings() throws IOException {
    AppConfig config =
        load(
            "custom.yml",
            """
            serviceName: custom
            server:
              httpsPort: 9443
              keyStorePath: /tmp/custom.p12
              supportedProtocols: [TLSv1.2, TLSv1.3]
            """);

    assertEquals(9443, config.server().httpsPort());
    assertEquals("/tmp/custom.p12", config.server().keyStorePath());
    assertEquals(List.of("TLSv1.2", "TLSv1.3"), config.server().supportedProtocols());
  }

  @Test
  void systemPropertiesOverrideConfiguredValues() {
    ServerConfig configured =
        new ServerConfig(8443, "server.p12", "changeit", "PKCS12", List.of("TLSv1.3"));

    System.setProperty(ServerConfig.HTTPS_PORT_PROPERTY, "9999");
    System.setProperty(ServerConfig.KEYSTORE_PATH_PROPERTY, "/tmp/override.p12");
    try {
      ServerConfig overridden = configured.withSystemPropertyOverrides();

      assertEquals(9999, overridden.httpsPort());
      assertEquals("/tmp/override.p12", overridden.keyStorePath());
      assertEquals("changeit", overridden.keyStorePassword());
    } finally {
      System.clearProperty(ServerConfig.HTTPS_PORT_PROPERTY);
      System.clearProperty(ServerConfig.KEYSTORE_PATH_PROPERTY);
    }
  }

  @Test
  void toStringMasksKeyStorePassword() {
    ServerConfig config =
        new ServerConfig(8443, "server.p12", "s3cr3t", "PKCS12", List.of("TLSv1.3"));

    assertFalse(config.toString().contains("s3cr3t"));
    assertTrue(config.toString().contains("keyStorePassword=***"));
  }

  private AppConfig load(String fileName, String yaml) throws IOException {
    Path file = tempDirectory.resolve(fileName);
    Files.writeString(file, yaml);
    return AppConfig.load(file);
  }
}
