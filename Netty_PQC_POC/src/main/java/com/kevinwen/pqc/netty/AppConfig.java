package com.kevinwen.pqc.netty;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * YAML-backed application configuration.
 *
 * <p>Every field is optional: absent or blank values fall back to the constants declared here, so a
 * config file may be as small as an empty document. Deployment-time overrides are supplied as system
 * properties and applied by {@link ServerConfig#withSystemPropertyOverrides()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppConfig(String serviceName, ServerConfig server) {

  private static final String DEFAULT_SERVICE_NAME = "netty-pqc-service";

  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

  public AppConfig {
    serviceName = orDefault(serviceName, DEFAULT_SERVICE_NAME);
    server = server != null ? server : ServerConfig.defaults();
  }

  /**
   * Reads a configuration document from disk.
   *
   * @param path YAML file to read
   * @throws IOException if the file cannot be read or is not valid YAML
   */
  public static AppConfig load(Path path) throws IOException {
    return YAML_MAPPER.readValue(path.toFile(), AppConfig.class);
  }

  private static String orDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  /** Listener and key material settings for the HTTPS endpoint. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ServerConfig(
      int httpsPort,
      String keyStorePath,
      String keyStorePassword,
      String keyStoreType,
      List<String> supportedProtocols) {

    static final String HTTPS_PORT_PROPERTY = "https.port";
    static final String KEYSTORE_PATH_PROPERTY = "keystore.path";
    static final String KEYSTORE_PASSWORD_PROPERTY = "keystore.password";
    static final String KEYSTORE_TYPE_PROPERTY = "keystore.type";

    private static final int DEFAULT_HTTPS_PORT = 8443;
    private static final String DEFAULT_KEYSTORE_PATH = "server.p12";
    private static final String DEFAULT_KEYSTORE_PASSWORD = "changeit";
    private static final String DEFAULT_KEYSTORE_TYPE = "PKCS12";
    private static final List<String> DEFAULT_PROTOCOLS = List.of("TLSv1.3");

    public ServerConfig {
      httpsPort = httpsPort > 0 ? httpsPort : DEFAULT_HTTPS_PORT;
      keyStorePath = orDefault(keyStorePath, DEFAULT_KEYSTORE_PATH);
      keyStorePassword = orDefault(keyStorePassword, DEFAULT_KEYSTORE_PASSWORD);
      keyStoreType = orDefault(keyStoreType, DEFAULT_KEYSTORE_TYPE);
      supportedProtocols =
          supportedProtocols == null || supportedProtocols.isEmpty()
              ? DEFAULT_PROTOCOLS
              : List.copyOf(supportedProtocols);
    }

    static ServerConfig defaults() {
      return new ServerConfig(0, null, null, null, null);
    }

    /**
     * Returns a copy in which any {@code -D} system property override replaces the configured value.
     * This keeps secrets and environment-specific ports out of the checked-in config file.
     */
    ServerConfig withSystemPropertyOverrides() {
      return new ServerConfig(
          Integer.getInteger(HTTPS_PORT_PROPERTY, httpsPort),
          System.getProperty(KEYSTORE_PATH_PROPERTY, keyStorePath),
          System.getProperty(KEYSTORE_PASSWORD_PROPERTY, keyStorePassword),
          System.getProperty(KEYSTORE_TYPE_PROPERTY, keyStoreType),
          supportedProtocols);
    }

    /** Masks the key store password so that logging a config never leaks it. */
    @Override
    public String toString() {
      return "ServerConfig[httpsPort=%d, keyStorePath=%s, keyStorePassword=***, keyStoreType=%s, supportedProtocols=%s]"
          .formatted(httpsPort, keyStorePath, keyStoreType, supportedProtocols);
    }
  }
}
