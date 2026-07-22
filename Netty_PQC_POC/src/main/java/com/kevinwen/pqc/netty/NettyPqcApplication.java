package com.kevinwen.pqc.netty;

import com.kevinwen.pqc.netty.AppConfig.ServerConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
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
 * exchange, which a PQC-capable client can verify. See {@code Readme.md}.
 *
 * <p>Usage: {@code java -jar netty-pqc-poc.jar [config.yml]}
 */
public final class NettyPqcApplication {

  private static final Logger LOG = LoggerFactory.getLogger(NettyPqcApplication.class);

  private static final String DEFAULT_CONFIG_PATH = "config.yml";

  /** Requests are tiny; the aggregator bound only needs to keep a malformed client in check. */
  private static final int MAX_HTTP_CONTENT_LENGTH = 64 * 1024;

  private static final int ACCEPT_BACKLOG = 128;

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

    run(sslContext, serverConfig.httpsPort(), new PqcStatusHandler(config.serviceName()));
  }

  /** Binds the listener and blocks until the server channel is closed. */
  private static void run(SslContext sslContext, int port, PqcStatusHandler statusHandler) {
    EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    try {
      ServerBootstrap bootstrap =
          new ServerBootstrap()
              .group(bossGroup, workerGroup)
              .channel(NioServerSocketChannel.class)
              .option(ChannelOption.SO_BACKLOG, ACCEPT_BACKLOG)
              .childHandler(new HttpsChannelInitializer(sslContext, statusHandler));

      Channel serverChannel = bootstrap.bind(port).syncUninterruptibly().channel();
      Runtime.getRuntime().addShutdownHook(shutdownHook(serverChannel, Thread.currentThread()));

      LOG.info("Listening on https://localhost:{}", port);
      LOG.info("  status : https://localhost:{}{}", port, PqcStatusHandler.PATH_STATUS);
      LOG.info("  tls    : https://localhost:{}{}", port, PqcStatusHandler.PATH_PQC);

      serverChannel.closeFuture().syncUninterruptibly();
    } finally {
      // awaitUninterruptibly, not sync: shutdown must complete even on an interrupted thread.
      workerGroup.shutdownGracefully().awaitUninterruptibly();
      bossGroup.shutdownGracefully().awaitUninterruptibly();
      LOG.info("Server stopped");
    }
  }

  /**
   * On SIGTERM/SIGINT, closes the listener and then waits for {@code serverThread} to finish its
   * cleanup. Without the join the JVM would halt as soon as the hook returned, cutting the graceful
   * event-loop shutdown short.
   */
  private static Thread shutdownHook(Channel serverChannel, Thread serverThread) {
    return new Thread(
        () -> {
          LOG.info("Shutdown signal received");
          serverChannel.close();
          try {
            serverThread.join(SHUTDOWN_TIMEOUT.toMillis());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        },
        "netty-pqc-shutdown");
  }

  /** TLS termination, HTTP/1.1 codec, request aggregation, then the application handler. */
  private static final class HttpsChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final SslContext sslContext;
    private final PqcStatusHandler statusHandler;

    private HttpsChannelInitializer(SslContext sslContext, PqcStatusHandler statusHandler) {
      this.sslContext = sslContext;
      this.statusHandler = statusHandler;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
      ChannelPipeline pipeline = channel.pipeline();
      pipeline.addLast(sslContext.newHandler(channel.alloc()));
      pipeline.addLast(new HttpServerCodec());
      pipeline.addLast(new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
      pipeline.addLast(statusHandler);
    }
  }
}
