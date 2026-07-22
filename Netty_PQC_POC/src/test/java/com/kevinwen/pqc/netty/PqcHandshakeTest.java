package com.kevinwen.pqc.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kevinwen.pqc.netty.AppConfig.ServerConfig;
import io.netty.handler.ssl.SslContext;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.jsse.BCSSLParameters;
import org.bouncycastle.jsse.BCSSLSocket;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves that the server really performs a post-quantum key exchange, rather than merely being
 * <em>asked</em> to.
 *
 * <p>The proof is by exclusion, and mirrors what {@code verify.sh} does with {@code openssl s_client
 * -groups X25519MLKEM768}: the client advertises the ML-KEM hybrid and nothing else. TLS 1.3 cannot
 * complete without a mutually supported group, so a successful handshake means the server chose the
 * hybrid and ML-KEM ran.
 *
 * <p>This is the regression guard for the defect described in {@link
 * PqcTlsSupport#registerProviders()}: when BCJSSE is not pinned to the BC provider, Bouncy Castle
 * silently disables every ML-KEM group. The old string-matching status endpoint still cheerfully
 * reported "enabled" in that state; this test instead fails with a handshake failure.
 */
class PqcHandshakeTest {

  private static final String HYBRID_GROUP = "X25519MLKEM768";

  private static final Path KEY_STORE = Path.of("server.p12");
  private static final String KEY_STORE_PASSWORD = "changeit";
  private static final String KEY_STORE_TYPE = "PKCS12";
  private static final String CERTIFICATE_ALIAS = "server";
  private static final String PROTOCOL = "TLSv1.3";

  @BeforeAll
  static void registerProviders() {
    PqcTlsSupport.registerProviders();
  }

  @Test
  void serverNegotiatesMlKemHybridWhenClientOffersOnlyThatGroup() throws Exception {
    assertTrue(Files.exists(KEY_STORE), "missing demo key store: " + KEY_STORE.toAbsolutePath());

    ServerConfig config =
        new ServerConfig(
            0, KEY_STORE.toString(), KEY_STORE_PASSWORD, KEY_STORE_TYPE, List.of(PROTOCOL));
    SslContext sslContext = PqcTlsSupport.newServerSslContext(config);

    try (NettyPqcServer server =
        new NettyPqcServer(sslContext, new PqcStatusHandler("handshake-test"))) {
      int port = server.start(0);

      String response = get(port, PqcStatusHandler.PATH_STATUS);

      assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
      assertTrue(response.contains("\"serviceName\" : \"handshake-test\""), response);
    }
  }

  /**
   * Performs one HTTPS GET over a BCJSSE client socket that offers {@link #HYBRID_GROUP} as its only
   * named group.
   */
  private static String get(int port, String path) throws Exception {
    SSLContext clientContext =
        SSLContext.getInstance(PROTOCOL, BouncyCastleJsseProvider.PROVIDER_NAME);
    clientContext.init(null, trustManagers(), null);

    try (SSLSocket socket =
        (SSLSocket) clientContext.getSocketFactory().createSocket("localhost", port)) {
      socket.setEnabledProtocols(new String[] {PROTOCOL});

      BCSSLSocket bcSocket = (BCSSLSocket) socket;
      BCSSLParameters parameters = bcSocket.getParameters();
      parameters.setNamedGroups(new String[] {HYBRID_GROUP});
      bcSocket.setParameters(parameters);

      socket.startHandshake();

      assertEquals(PROTOCOL, socket.getSession().getProtocol());
      assertEquals(
          List.of(HYBRID_GROUP),
          List.of(bcSocket.getParameters().getNamedGroups()),
          "client must have offered only the hybrid group");

      OutputStream out = socket.getOutputStream();
      out.write(
          ("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8));
      out.flush();

      // The handler sets Connection: close, so the response ends at EOF.
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** Trusts exactly the self-signed certificate held in the demo key store. */
  private static TrustManager[] trustManagers() throws Exception {
    KeyStore keyStore = KeyStore.getInstance(KEY_STORE_TYPE);
    try (InputStream keyStoreStream = Files.newInputStream(KEY_STORE)) {
      keyStore.load(keyStoreStream, KEY_STORE_PASSWORD.toCharArray());
    }

    KeyStore trustStore = KeyStore.getInstance(KEY_STORE_TYPE);
    trustStore.load(null, null);
    trustStore.setCertificateEntry(CERTIFICATE_ALIAS, keyStore.getCertificate(CERTIFICATE_ALIAS));

    TrustManagerFactory trustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(trustStore);
    return trustManagerFactory.getTrustManagers();
  }
}
