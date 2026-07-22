package com.kevinwen.pqc.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Drives the handler through an {@link EmbeddedChannel}, which exercises the real Netty pipeline
 * contract without binding a socket or performing a TLS handshake.
 */
class PqcStatusHandlerTest {

  private static final String SERVICE_NAME = "test-pqc-service";
  private static final ObjectMapper JSON = new ObjectMapper();

  @BeforeAll
  static void registerProviders() {
    // /crypto/status digests through Bouncy Castle, so the provider must be installed.
    PqcTlsSupport.registerProviders();
  }

  @Test
  void statusEndpointReturnsJsonPayload() throws Exception {
    Response response = get(PqcStatusHandler.PATH_STATUS);

    assertEquals(HttpResponseStatus.OK, response.status());
    assertEquals("application/json; charset=UTF-8", response.contentType());

    JsonNode payload = JSON.readTree(response.body());
    assertEquals(SERVICE_NAME, payload.get("serviceName").asText());
    assertEquals("BC", payload.get("provider").asText());
    assertEquals("SHA3-256", payload.get("algorithm").asText());
    assertEquals(8, payload.get("sampleHashPrefix").asText().length());
    assertNotNull(payload.get("timestamp"));
  }

  @Test
  void pqcEndpointReportsTlsProviderAndGroups() {
    Response response = get(PqcStatusHandler.PATH_PQC);

    assertEquals(HttpResponseStatus.OK, response.status());
    assertEquals("text/plain; charset=UTF-8", response.contentType());
    assertTrue(response.body().contains("TLS Provider:"), response.body());
    assertTrue(response.body().contains("Configured TLS Groups"), response.body());
    assertTrue(response.body().contains("ML-KEM Hybrid Requested:"), response.body());
    // The provider probe must succeed, otherwise no ML-KEM group can ever be negotiated.
    assertTrue(response.body().contains("ML-KEM Key Generation Available: YES"), response.body());
  }

  @Test
  void pqcEndpointReportsHybridEnabledWhenMlKemGroupIsConfigured() {
    String previous = System.getProperty(PqcStatusHandler.NAMED_GROUPS_PROPERTY);
    System.setProperty(PqcStatusHandler.NAMED_GROUPS_PROPERTY, "X25519MLKEM768,secp256r1");
    try {
      assertTrue(get(PqcStatusHandler.PATH_PQC).body().contains("ML-KEM Hybrid Requested: YES"));
    } finally {
      if (previous == null) {
        System.clearProperty(PqcStatusHandler.NAMED_GROUPS_PROPERTY);
      } else {
        System.setProperty(PqcStatusHandler.NAMED_GROUPS_PROPERTY, previous);
      }
    }
  }

  @Test
  void unknownPathReturnsNotFound() {
    assertEquals(HttpResponseStatus.NOT_FOUND, get("/nope").status());
  }

  @Test
  void queryStringIsIgnoredWhenRouting() {
    assertEquals(HttpResponseStatus.OK, get(PqcStatusHandler.PATH_PQC + "?verbose=true").status());
  }

  @Test
  void nonGetMethodIsRejected() {
    Response response =
        exchange(
            new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                PqcStatusHandler.PATH_STATUS,
                Unpooled.EMPTY_BUFFER));

    assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, response.status());
  }

  private static Response get(String uri) {
    return exchange(
        new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, uri, Unpooled.EMPTY_BUFFER));
  }

  /** Runs one request through a fresh pipeline and copies the response out of Netty's buffers. */
  private static Response exchange(FullHttpRequest request) {
    EmbeddedChannel channel = new EmbeddedChannel(new PqcStatusHandler(SERVICE_NAME));
    try {
      channel.writeInbound(request);

      FullHttpResponse response = channel.readOutbound();
      assertNotNull(response, "handler produced no response");
      try {
        return new Response(
            response.status(),
            response.headers().get(HttpHeaderNames.CONTENT_TYPE),
            response.content().toString(StandardCharsets.UTF_8));
      } finally {
        ReferenceCountUtil.release(response);
      }
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  private record Response(HttpResponseStatus status, String contentType, String body) {}
}
