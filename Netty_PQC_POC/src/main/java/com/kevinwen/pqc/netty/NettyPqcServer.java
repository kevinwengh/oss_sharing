package com.kevinwen.pqc.netty;

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
import java.net.InetSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lifecycle of the HTTPS listener, separated from {@link NettyPqcApplication} so that tests can bind
 * an ephemeral port and drive a real TLS handshake against it.
 *
 * <p>Not thread-safe: {@link #start(int)} and {@link #close()} are expected to be called from the
 * owning thread (plus a shutdown hook calling {@link #stopAccepting()}).
 */
final class NettyPqcServer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(NettyPqcServer.class);

  /** Requests are tiny; the aggregator bound only needs to keep a malformed client in check. */
  private static final int MAX_HTTP_CONTENT_LENGTH = 64 * 1024;

  private static final int ACCEPT_BACKLOG = 128;

  private final SslContext sslContext;
  private final PqcStatusHandler statusHandler;
  private final EventLoopGroup bossGroup;
  private final EventLoopGroup workerGroup;

  private volatile Channel serverChannel;

  NettyPqcServer(SslContext sslContext, PqcStatusHandler statusHandler) {
    this.sslContext = sslContext;
    this.statusHandler = statusHandler;
    this.bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    this.workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
  }

  /**
   * Binds the listener.
   *
   * @param port port to bind, or {@code 0} to let the OS pick one
   * @return the port actually bound
   */
  int start(int port) {
    ServerBootstrap bootstrap =
        new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .option(ChannelOption.SO_BACKLOG, ACCEPT_BACKLOG)
            .childHandler(new HttpsChannelInitializer(sslContext, statusHandler));

    serverChannel = bootstrap.bind(port).syncUninterruptibly().channel();
    return ((InetSocketAddress) serverChannel.localAddress()).getPort();
  }

  /** Blocks until the listener is closed. */
  void awaitShutdown() {
    serverChannel.closeFuture().syncUninterruptibly();
  }

  /** Closes the listener, which releases {@link #awaitShutdown()}. Safe to call more than once. */
  void stopAccepting() {
    Channel channel = serverChannel;
    if (channel != null) {
      channel.close();
    }
  }

  @Override
  public void close() {
    stopAccepting();
    // awaitUninterruptibly, not sync: shutdown must complete even on an interrupted thread.
    workerGroup.shutdownGracefully().awaitUninterruptibly();
    bossGroup.shutdownGracefully().awaitUninterruptibly();
    LOG.info("Server stopped");
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
