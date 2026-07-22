package com.kevinwen.pqc.netty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Provider;
import java.util.HexFormat;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only endpoints that report which crypto stack the JVM is running.
 *
 * <p>The handler holds no per-connection state, so a single instance is shared across all channels.
 * Every response closes the connection: these are diagnostic endpoints hit one request at a time,
 * and closing keeps each answer tied to exactly one TLS handshake.
 */
@Sharable
final class PqcStatusHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

  static final String PATH_PQC = "/crypto/pqc";
  static final String PATH_STATUS = "/crypto/status";

  /** JVM property that selects the TLS named groups offered during the handshake. */
  static final String NAMED_GROUPS_PROPERTY = "jdk.tls.namedGroups";

  private static final Logger LOG = LoggerFactory.getLogger(PqcStatusHandler.class);
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

  private static final String DIGEST_ALGORITHM = "SHA3-256";
  private static final String DIGEST_SAMPLE_INPUT = "netty-pqc";
  private static final int DIGEST_PREFIX_BYTES = 4;

  private static final String TEXT_PLAIN = "text/plain; charset=UTF-8";
  private static final String APPLICATION_JSON = "application/json; charset=UTF-8";

  private final String serviceName;

  PqcStatusHandler(String serviceName) {
    this.serviceName = serviceName;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
    if (!request.decoderResult().isSuccess()) {
      sendText(ctx, HttpResponseStatus.BAD_REQUEST, "Invalid HTTP request");
      return;
    }
    if (!HttpMethod.GET.equals(request.method())) {
      sendText(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only GET is supported");
      return;
    }

    String path = new QueryStringDecoder(request.uri()).path();
    try {
      switch (path) {
        case PATH_PQC -> sendText(ctx, HttpResponseStatus.OK, describeTlsStack());
        case PATH_STATUS -> sendJson(ctx, HttpResponseStatus.OK, buildCryptoStatus());
        default -> sendText(ctx, HttpResponseStatus.NOT_FOUND, "Not Found");
      }
    } catch (GeneralSecurityException | JsonProcessingException e) {
      LOG.error("Failed to build response for {}", path, e);
      sendText(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
    }
  }

  /** Renders the active TLS provider, its cipher suites and whether ML-KEM groups are configured. */
  private static String describeTlsStack() throws GeneralSecurityException {
    SSLContext sslContext = SSLContext.getDefault();
    Provider provider = sslContext.getProvider();
    SSLParameters parameters = sslContext.getSupportedSSLParameters();

    String namedGroups = System.getProperty(NAMED_GROUPS_PROPERTY, "default");

    return """
        TLS Provider: %s %s
        Provider Class: %s

        Supported Cipher Suites:
        %s

        Configured TLS Groups (requested via -D%s):
        %s

        ML-KEM Hybrid Requested: %s
        ML-KEM Key Generation Available: %s

        Requesting a group is not the same as negotiating one -- run verify.sh to confirm what a
        client actually gets.
        """
        .formatted(
            provider.getName(),
            provider.getVersionStr(),
            provider.getClass().getName(),
            String.join("\n", parameters.getCipherSuites()),
            NAMED_GROUPS_PROPERTY,
            namedGroups,
            yesNo(isMlKemRequested(namedGroups)),
            yesNo(PqcTlsSupport.isMlKemAvailable()));
  }

  private static boolean isMlKemRequested(String namedGroups) {
    return namedGroups.toUpperCase(Locale.ROOT).contains("MLKEM");
  }

  private static String yesNo(boolean value) {
    return value ? "YES" : "NO";
  }

  /** Digests a fixed input through Bouncy Castle, proving the provider is reachable at runtime. */
  private CryptoStatus buildCryptoStatus() throws GeneralSecurityException {
    MessageDigest digest =
        MessageDigest.getInstance(DIGEST_ALGORITHM, BouncyCastleProvider.PROVIDER_NAME);
    byte[] hash = digest.digest(DIGEST_SAMPLE_INPUT.getBytes(StandardCharsets.UTF_8));

    return CryptoStatus.of(
        serviceName,
        BouncyCastleProvider.PROVIDER_NAME,
        DIGEST_ALGORITHM,
        HexFormat.of().formatHex(hash, 0, DIGEST_PREFIX_BYTES));
  }

  private static void sendText(ChannelHandlerContext ctx, HttpResponseStatus status, String body) {
    send(ctx, status, TEXT_PLAIN, body.getBytes(StandardCharsets.UTF_8));
  }

  private static void sendJson(ChannelHandlerContext ctx, HttpResponseStatus status, Object payload)
      throws JsonProcessingException {
    byte[] body = JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
    send(ctx, status, APPLICATION_JSON, body);
  }

  private static void send(
      ChannelHandlerContext ctx, HttpResponseStatus status, String contentType, byte[] body) {
    FullHttpResponse response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    LOG.error("Closing {} after unhandled exception", ctx.channel().remoteAddress(), cause);
    ctx.close();
  }
}
