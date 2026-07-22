package com.kevinwen.pqc.netty;

import com.kevinwen.pqc.netty.AppConfig.ServerConfig;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.bouncycastle.jcajce.spec.MLKEMParameterSpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wiring for the Bouncy Castle crypto stack.
 *
 * <p>The JDK's own TLS implementation is bypassed entirely: BCJSSE is installed as the default
 * {@link SSLContext} provider and Netty is pointed at the same provider, which is what makes the
 * post-quantum hybrid groups (for example {@code X25519MLKEM768}) available during the handshake.
 * The groups themselves are selected by the {@code jdk.tls.namedGroups} system property at JVM
 * start-up; see {@code run.sh}.
 */
final class PqcTlsSupport {

  private static final Logger LOG = LoggerFactory.getLogger(PqcTlsSupport.class);

  private PqcTlsSupport() {}

  /**
   * Installs the Bouncy Castle JCE and JSSE providers if they are not registered already.
   *
   * <p>BCJSSE is deliberately pinned to the BC provider instance rather than constructed with the
   * no-arg constructor. The no-arg form resolves JCA services across every installed provider, so on
   * a JDK that ships its own ML-KEM (24+) {@code KeyPairGenerator.getInstance("ML-KEM")} returns
   * SunJCE, which then rejects Bouncy Castle's {@code MLKEMParameterSpec}. Bouncy Castle treats that
   * failure as "algorithm unsupported" and silently drops <em>every</em> ML-KEM named group — the
   * handshake falls back to classical key exchange with only a {@code WARNING: 'jdk.tls.namedGroups'
   * contains disabled NamedGroup} line in the log to show for it.
   */
  static void registerProviders() {
    Provider bouncyCastle = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
    if (bouncyCastle == null) {
      bouncyCastle = new BouncyCastleProvider();
      Security.addProvider(bouncyCastle);
    }
    if (Security.getProvider(BouncyCastleJsseProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleJsseProvider(bouncyCastle));
    }
    LOG.info(
        "Registered security providers: {}, {} (crypto pinned to {})",
        BouncyCastleProvider.PROVIDER_NAME,
        BouncyCastleJsseProvider.PROVIDER_NAME,
        BouncyCastleProvider.PROVIDER_NAME);
  }

  /**
   * Makes BCJSSE the JVM-wide default {@link SSLContext}, so that code reading {@code
   * SSLContext.getDefault()} — including the status endpoint — reports the same stack the server
   * actually negotiates with.
   */
  static void installAsDefaultSslContext() throws GeneralSecurityException {
    SSLContext sslContext = SSLContext.getInstance("TLS", BouncyCastleJsseProvider.PROVIDER_NAME);
    sslContext.init(null, null, null);
    SSLContext.setDefault(sslContext);
    LOG.info("Default SSLContext provider is now {}", sslContext.getProvider().getName());
  }

  /**
   * Checks that Bouncy Castle can actually produce ML-KEM key material, which is the capability the
   * hybrid named groups are built on. This is a live probe rather than a string match against {@code
   * jdk.tls.namedGroups}, because requesting a group is not the same as supporting it.
   *
   * <p>Note the limit of what this proves: it asks BC directly, so it stays {@code true} even when
   * BCJSSE is mis-registered and has disabled every ML-KEM group (see {@link #registerProviders()}).
   * Only a real handshake settles that — {@code PqcHandshakeTest} in the build, {@code verify.sh}
   * against an external client.
   */
  static boolean isMlKemAvailable() {
    try {
      KeyPairGenerator generator =
          KeyPairGenerator.getInstance("ML-KEM", BouncyCastleProvider.PROVIDER_NAME);
      generator.initialize(MLKEMParameterSpec.ml_kem_768, new SecureRandom());
      return true;
    } catch (GeneralSecurityException e) {
      LOG.warn("Bouncy Castle cannot generate ML-KEM key pairs", e);
      return false;
    }
  }

  /**
   * Builds the server-side {@link SslContext} from the configured key store.
   *
   * <p>{@link SslProvider#JDK} is required here: it is the only Netty provider that delegates to a
   * JCA {@link Provider}, and therefore the only one that can route the handshake through BCJSSE.
   *
   * @throws IOException if the key store cannot be read
   * @throws GeneralSecurityException if the key store cannot be loaded or contains no usable key
   */
  static SslContext newServerSslContext(ServerConfig config)
      throws IOException, GeneralSecurityException {
    KeyManagerFactory keyManagerFactory = loadKeyManagerFactory(config);
    String[] protocols = config.supportedProtocols().toArray(String[]::new);

    LOG.info(
        "Building server SslContext from {} ({}), protocols={}",
        config.keyStorePath(),
        config.keyStoreType(),
        config.supportedProtocols());

    return SslContextBuilder.forServer(keyManagerFactory)
        .sslProvider(SslProvider.JDK)
        .sslContextProvider(Security.getProvider(BouncyCastleJsseProvider.PROVIDER_NAME))
        .protocols(protocols)
        .build();
  }

  private static KeyManagerFactory loadKeyManagerFactory(ServerConfig config)
      throws IOException, GeneralSecurityException {
    char[] password = config.keyStorePassword().toCharArray();
    try {
      KeyStore keyStore = KeyStore.getInstance(config.keyStoreType());
      try (InputStream keyStoreStream = Files.newInputStream(Path.of(config.keyStorePath()))) {
        keyStore.load(keyStoreStream, password);
      }

      KeyManagerFactory keyManagerFactory =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keyManagerFactory.init(keyStore, password);
      return keyManagerFactory;
    } finally {
      // The password array is the only copy under our control; clear it once the key is loaded.
      Arrays.fill(password, '\0');
    }
  }
}
