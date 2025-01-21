/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package linktiger.http;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class ConnectionReuseTest {
  @Rule public final TestRule timeout = new Timeout(30_000);
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final HttpClientTestRule clientTestRule = new HttpClientTestRule();

  private HandshakeCertificates handshakeCertificates = localhost();
  private HttpClient client = clientTestRule.client;

  @Test public void connectionsAreReused() throws Exception {
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .build();
    assertConnectionReused(request, request);
  }

  @Test public void connectionsAreNotReusedWithRequestConnectionClose() throws Exception {
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    Request requestA = new Request.Builder()
        .url(server.url("/").toString())
        .header("Connection", "close")
        .build();
    Request requestB = new Request.Builder()
        .url(server.url("/").toString())
        .build();
    assertConnectionNotReused(requestA, requestB);
  }

  @Test public void connectionsAreNotReusedWithResponseConnectionClose() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Connection", "close")
        .setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    Request requestA = new Request.Builder()
        .url(server.url("/").toString())
        .build();
    Request requestB = new Request.Builder()
        .url(server.url("/").toString())
        .build();
    assertConnectionNotReused(requestA, requestB);
  }

  @Test public void connectionsAreNotReusedWithUnknownLengthResponseBody() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("a")
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse().setBody("b"));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .build();
    assertConnectionNotReused(request, request);
  }

  @Test public void connectionsAreNotReusedIfPoolIsSizeZero() throws Exception {
    client = client.newBuilder()
        .connectionPool(new ConnectionPool(0, 5, TimeUnit.SECONDS))
        .build();
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .build();
    assertConnectionNotReused(request, request);
  }

  @Test public void silentRetryWhenIdempotentRequestFailsOnReusedConnection() throws Exception {
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    server.enqueue(new MockResponse().setBody("b"));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .build();

    Response responseA = client.newCall(request).execute();
    assertThat(responseA.body().bytes()).isEqualTo("a".getBytes(StandardCharsets.UTF_8));
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);

    Response responseB = client.newCall(request).execute();
    assertThat(responseB.body().bytes()).isEqualTo("b".getBytes(StandardCharsets.UTF_8));
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
  }

  @Test public void staleConnectionNotReusedForNonIdempotentRequest() throws Exception {
    server.enqueue(new MockResponse().setBody("a")
        .setSocketPolicy(SocketPolicy.SHUTDOWN_OUTPUT_AT_END));
    server.enqueue(new MockResponse().setBody("b"));

    Request requestA = new Request.Builder()
        .url(server.url("/").toString())
        .build();
    Response responseA = client.newCall(requestA).execute();
    assertThat(responseA.body().bytes()).isEqualTo("a".getBytes(StandardCharsets.UTF_8));
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);

    // Give the socket a chance to become stale.
    Thread.sleep(250);

    Request requestB = new Request.Builder()
        .url(server.url("/").toString())
        .post(RequestBody.create(MediaType.get("text/plain"), "b"))
        .build();
    Response responseB = client.newCall(requestB).execute();
    assertThat(responseB.body().bytes()).isEqualTo("b".getBytes(StandardCharsets.UTF_8));
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
  }

  @Test public void connectionsAreEvicted() throws Exception {
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    client = client.newBuilder()
        .connectionPool(new ConnectionPool(5, 250, TimeUnit.MILLISECONDS))
        .build();
    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .build();

    Response response1 = client.newCall(request).execute();
    assertThat(response1.body().bytes()).isEqualTo("a".getBytes(StandardCharsets.UTF_8));

    // Give the thread pool a chance to evict.
    Thread.sleep(500);

    Response response2 = client.newCall(request).execute();
    assertThat(response2.body().bytes()).isEqualTo("b".getBytes(StandardCharsets.UTF_8));

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
  }

  @Test public void connectionsAreNotReusedIfSslSocketFactoryChanges() throws Exception {
    enableHttps();
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .build();

    Response response = client.newCall(request).execute();
    response.body().close();

    // This client shares a connection pool but has a different SSL socket factory.
    HandshakeCertificates handshakeCertificates2 = new HandshakeCertificates.Builder().build();
    HttpClient anotherClient = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates2.sslSocketFactory(), handshakeCertificates2.trustManager())
        .build();

    // This client fails to connect because the new SSL socket factory refuses.
    try {
      anotherClient.newCall(request).execute();
      fail();
    } catch (SSLException expected) {
    }
  }

  @Test public void connectionsAreNotReusedIfHostnameVerifierChanges() throws Exception {
    enableHttps();
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .build();

    Response response1 = client.newCall(request).execute();
    response1.body().close();

    // This client shares a connection pool but has a different SSL socket factory.
    HttpClient anotherClient = client.newBuilder()
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    Response response2 = anotherClient.newCall(request).execute();
    response2.body().close();

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
  }

  private void enableHttps() {
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.setProtocols(List.of(okhttp3.Protocol.HTTP_1_1));
  }

  private void assertConnectionReused(Request... requests) throws Exception {
    for (int i = 0; i < requests.length; i++) {
      Response response = client.newCall(requests[i]).execute();
      response.body().bytes(); // Discard the response body.
      assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(i);
    }
  }

  private void assertConnectionNotReused(Request... requests) throws Exception {
    for (Request request : requests) {
      Response response = client.newCall(request).execute();
      response.body().bytes(); // Discard the response body.
      assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    }
  }
}
