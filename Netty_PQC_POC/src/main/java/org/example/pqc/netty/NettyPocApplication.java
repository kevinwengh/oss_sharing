/*
 * Personal property of Kevin Wen (Kevinwen@gmail.com).
 * A written permission is required for distribution and re-use of any code from this repo
 */
package org.example.pqc.netty;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.Security;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyPocApplication {

  private static final Logger LOG = LoggerFactory.getLogger(NettyPocApplication.class);
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

  public static void main(String[] args) throws Exception {
    String configPath = args.length > 0 ? args[0] : "config.yml";
    AppConfig appConfig = loadConfig(configPath);

    registerProviders();
    configureDefaultSslContext();

    SslContext sslContext = buildNettySslContext(appConfig);
    int httpsPort = Integer.getInteger("https.port", appConfig.server.httpsPort);

    NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
    NioEventLoopGroup workerGroup = new NioEventLoopGroup();
    try {
      ServerBootstrap bootstrap = new ServerBootstrap();
      bootstrap
          .group(bossGroup, workerGroup)
          .channel(NioServerSocketChannel.class)
          .childHandler(
              new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                  ChannelPipeline pipeline = ch.pipeline();
                  pipeline.addLast(sslContext.newHandler(ch.alloc()));
                  pipeline.addLast(new HttpServerCodec());
                  pipeline.addLast(new HttpObjectAggregator(1024 * 1024));
                  pipeline.addLast(new PqcHttpHandler(appConfig.serviceName));
                }
              });

      Channel channel = bootstrap.bind(httpsPort).sync().channel();
      LOG.info("Netty_Poc server started on https://localhost:{}", httpsPort);
      LOG.info("Try endpoint: https://localhost:{}/crypto/pqc", httpsPort);
      channel.closeFuture().sync();
    } finally {
      workerGroup.shutdownGracefully().sync();
      bossGroup.shutdownGracefully().sync();
    }
  }

  static void registerProviders() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    if (Security.getProvider(BouncyCastleJsseProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleJsseProvider());
    }
    LOG.info(
        "Registered providers: {}, {}",
        BouncyCastleProvider.PROVIDER_NAME,
        BouncyCastleJsseProvider.PROVIDER_NAME);
  }

  private static void configureDefaultSslContext() throws Exception {
    SSLContext sslContext = SSLContext.getInstance("TLS", BouncyCastleJsseProvider.PROVIDER_NAME);
    sslContext.init(null, null, null);
    SSLContext.setDefault(sslContext);
    LOG.info("Configured default SSLContext provider={}", SSLContext.getDefault().getProvider());
  }

  private static SslContext buildNettySslContext(AppConfig appConfig) throws Exception {
    String keyStorePath = System.getProperty("keystore.path", appConfig.server.keyStorePath);
    String keyStoreType = System.getProperty("keystore.type", appConfig.server.keyStoreType);
    char[] keyStorePassword =
        System.getProperty("keystore.password", appConfig.server.keyStorePassword).toCharArray();

    KeyStore keyStore = KeyStore.getInstance(keyStoreType);
    try (FileInputStream inputStream = new FileInputStream(keyStorePath)) {
      keyStore.load(inputStream, keyStorePassword);
    }

    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keyStore, keyStorePassword);

    return SslContextBuilder.forServer(kmf)
        .sslProvider(SslProvider.JDK)
        .sslContextProvider(Security.getProvider(BouncyCastleJsseProvider.PROVIDER_NAME))
        .protocols("TLSv1.3")
        .build();
  }

  static AppConfig loadConfig(String path) throws Exception {
    AppConfig config = YAML_MAPPER.readValue(new java.io.File(path), AppConfig.class);
    if (config.server == null) {
      config.server = new ServerConfig();
    }
    if (config.server.supportedProtocols == null || config.server.supportedProtocols.isEmpty()) {
      config.server.supportedProtocols = Collections.singletonList("TLSv1.3");
    }
    return config;
  }

  private static final class PqcHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final String serviceName;

    private PqcHttpHandler(String serviceName) {
      this.serviceName = serviceName;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request)
        throws Exception {
      if (!request.decoderResult().isSuccess()) {
        sendText(ctx, HttpResponseStatus.BAD_REQUEST, "Invalid HTTP request");
        return;
      }

      if (request.method() != HttpMethod.GET) {
        sendText(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only GET is supported");
        return;
      }

      String path = new QueryStringDecoder(request.uri()).path();
      if ("/crypto/pqc".equals(path)) {
        sendText(ctx, HttpResponseStatus.OK, buildPqcStatus());
      } else if ("/crypto/status".equals(path)) {
        sendJson(ctx, HttpResponseStatus.OK, buildStatusPayload());
      } else {
        sendText(ctx, HttpResponseStatus.NOT_FOUND, "Not Found");
      }
    }

    private String buildPqcStatus() throws Exception {
      SSLContext ctx = SSLContext.getDefault();
      Provider provider = ctx.getProvider();
      SSLParameters params = ctx.getSupportedSSLParameters();

      String groups = System.getProperty("jdk.tls.namedGroups", "default");
      boolean mlkemEnabled = groups.contains("MLKEM") || groups.contains("mlkem");

      return ""
          + "TLS Provider: "
          + provider.getName()
          + " "
          + provider.getVersionStr()
          + "\n"
          + "Provider Class: "
          + provider.getClass().getName()
          + "\n\n"
          + "Supported Cipher Suites:\n"
          + Arrays.stream(params.getCipherSuites()).limit(100).collect(Collectors.joining("\n"))
          + "\n...\n\n"
          + "Configured TLS Groups:\n"
          + groups
          + "\n\n"
          + "ML-KEM Hybrid Enabled: "
          + (mlkemEnabled ? "YES" : "NO");
    }

    private Map<String, Object> buildStatusPayload() throws Exception {
      MessageDigest digest =
          MessageDigest.getInstance("SHA3-256", BouncyCastleProvider.PROVIDER_NAME);
      byte[] hash = digest.digest("dropwizard-pqc".getBytes(StandardCharsets.UTF_8));

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("serviceName", serviceName);
      result.put("provider", BouncyCastleProvider.PROVIDER_NAME);
      result.put("algorithm", "SHA3-256");
      result.put("sampleHashPrefix", toHex(hash).substring(0, 8));
      result.put("timestamp", Instant.now().toString());
      return result;
    }

    private String toHex(byte[] data) {
      StringBuilder builder = new StringBuilder();
      for (byte b : data) {
        builder.append(String.format("%02x", b));
      }
      return builder.toString();
    }

    private void sendText(ChannelHandlerContext ctx, HttpResponseStatus status, String body) {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      FullHttpResponse response =
          new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
      response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
      response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
      response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
      ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void sendJson(ChannelHandlerContext ctx, HttpResponseStatus status, Object payload)
        throws Exception {
      byte[] bytes = JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
      FullHttpResponse response =
          new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
      response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
      response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
      response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
      ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      LOG.error("Unhandled exception in HTTP handler", cause);
      ctx.close();
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static final class AppConfig {
    @JsonProperty("serviceName")
    String serviceName = "dropwizard-pqc-service";

    @JsonProperty("server")
    ServerConfig server = new ServerConfig();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static final class ServerConfig {
    @JsonProperty("httpsPort")
    int httpsPort = 8443;

    @JsonProperty("keyStorePath")
    String keyStorePath = "server.p12";

    @JsonProperty("keyStorePassword")
    String keyStorePassword = "changeit";

    @JsonProperty("keyStoreType")
    String keyStoreType = "PKCS12";

    @JsonProperty("supportedProtocols")
    List<String> supportedProtocols = Collections.singletonList("TLSv1.3");
  }
}
