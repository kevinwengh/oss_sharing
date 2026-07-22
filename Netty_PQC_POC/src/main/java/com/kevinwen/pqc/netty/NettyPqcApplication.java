package com.kevinwen.pqc.netty;

import com.kevinwen.pqc.netty.AppConfig.ServerConfig;
import io.netty.handler.ssl.SslContext;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTPS server that terminates TLS 1.3 through the Bouncy Castle JSSE provider and exposes two
 * read-only endpoints describing the crypto stack in use.
 *
 * <p>The point of the POC is the handshake, not the endpoints: with {@code
 * -Djdk.tls.namedGroups=X25519MLKEM768,...} the server negotiates a post-quantum hybrid key
 * exchange. {@code PqcHandshakeTest} proves it in the build, and {@code verify.sh} proves it against
 * an external OpenSSL client. See {@code Readme.md}.
 *
 * <p>Usage: {@code java -jar netty-pqc-poc.jar [config.yml]}
 */
public final class NettyPqcApplication {

  private static final Logger LOG = LoggerFactory.getLogger(NettyPqcApplication.class);

  private static final String DEFAULT_CONFIG_PATH = "config.yml";

  /** How long the shutdown hook waits for the event loops to drain before letting the JVM exit. */
  private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

  private NettyPqcApplication() {}

  public static void main(String[] args) throws IOException, GeneralSecurityException {
    Path configPath = Path.of(args.length > 0 ? args[0] : DEFAULT_CONFIG_PATH);
    AppConfig config = AppConfig.load(configPath);
    ServerConfig serverConfig = config.server().withSystemPropertyOverrides();
    LOG.info("Loaded configuration from {}: {}", configPath.toAbsolutePath(), serverConfig);

    PqcTlsSupport.registerProviders();
    PqcTlsSupport.installAsDefaultSslContext();
    SslContext sslContext = PqcTlsSupport.newServerSslContext(serverConfig);

    try (NettyPqcServer server =
        new NettyPqcServer(sslContext, new PqcStatusHandler(config.serviceName()))) {
      int port = server.start(serverConfig.httpsPort());
      Runtime.getRuntime().addShutdownHook(shutdownHook(server, Thread.currentThread()));

      LOG.info("Listening on https://localhost:{}", port);
      LOG.info("  status : https://localhost:{}{}", port, PqcStatusHandler.PATH_STATUS);
      LOG.info("  tls    : https://localhost:{}{}", port, PqcStatusHandler.PATH_PQC);

      server.awaitShutdown();
    }
  }

  /**
   * On SIGTERM/SIGINT, closes the listener and then waits for {@code serverThread} to finish its
   * cleanup. Without the join the JVM would halt as soon as the hook returned, cutting the graceful
   * event-loop shutdown short.
   */
  private static Thread shutdownHook(NettyPqcServer server, Thread serverThread) {
    return new Thread(
        () -> {
          LOG.info("Shutdown signal received");
          server.stopAccepting();
          try {
            serverThread.join(SHUTDOWN_TIMEOUT.toMillis());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        },
        "netty-pqc-shutdown");
  }
}
