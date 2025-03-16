/*
 * Copyright (C) 2013 Square, Inc.
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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLProtocolException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import okhttp3.internal.Internal;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import kio.BufferedSink;
import kio.BufferedSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;

public final class CallTest {
  @Rule public final TestRule timeout = new Timeout(30_000, TimeUnit.MILLISECONDS);
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final MockWebServer server2 = new MockWebServer();
  @Rule public final HttpClientTestRule clientTestRule = new HttpClientTestRule();

  private HandshakeCertificates handshakeCertificates = localhost();
  private HttpClient client = clientTestRule.client.newBuilder().build();
  private TestLogHandler logHandler = new TestLogHandler();
  private Logger logger = Logger.getLogger(HttpClient.class.getName());

  @Before public void setUp() throws Exception {
    logger.addHandler(logHandler);
  }

  @After public void tearDown() throws Exception {
    logger.removeHandler(logHandler);
  }

  @Test public void get() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc")
        .clearHeaders()
        .addHeader("content-type: text/plain")
        .addHeader("content-length", "3"));

    long sentAt = System.currentTimeMillis();
    RecordedResponse recordedResponse = executeSynchronously("/", "User-Agent", "SyncApiTest");
    long receivedAt = System.currentTimeMillis();

    recordedResponse.assertCode(200)
        .assertSuccessful()
        .assertHeaders(new Headers.Builder()
            .add("content-type", "text/plain")
            .add("content-length", "3")
            .build())
        .assertBody("abc")
        .assertSentRequestAtMillis(sentAt, receivedAt)
        .assertReceivedResponseAtMillis(sentAt, receivedAt);

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("GET");
    assertThat(recordedRequest.getHeader("User-Agent")).isEqualTo("SyncApiTest");
    assertThat(recordedRequest.getBody().size()).isEqualTo(0);
    assertThat(recordedRequest.getHeader("Content-Length")).isNull();
  }

  @Test public void buildRequestUsingHttpUrl() throws Exception {
    server.enqueue(new MockResponse());
    executeSynchronously("/").assertSuccessful();
  }

  @Test public void invalidScheme() throws Exception {
    Request.Builder requestBuilder = new Request.Builder();
    try {
      requestBuilder.url("ftp://hostname/path");
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          "Expected URL scheme 'http' or 'https' but was 'ftp'");
    }
  }

  @Test public void invalidPort() throws Exception {
    Request.Builder requestBuilder = new Request.Builder();
    try {
      requestBuilder.url("http://localhost:65536/");
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("Invalid URL port: \"65536\"");
    }
  }

  @Test public void getReturns500() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500));
    executeSynchronously("/")
        .assertCode(500)
        .assertNotSuccessful();
  }

  @Test public void get_HTTPS() throws Exception {
    enableTls();
    get();
  }

  @Test public void repeatedHeaderNames() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("B", "123")
        .addHeader("B", "234"));

    executeSynchronously("/", "A", "345", "A", "456")
        .assertCode(200)
        .assertHeader("B", "123", "234");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getHeaders().values("A")).isEqualTo(
        Arrays.asList("345", "456"));
  }

  @Test public void head() throws Exception {
    server.enqueue(new MockResponse().addHeader("Content-Type: text/plain"));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .head()
        .header("User-Agent", "SyncApiTest")
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertHeader("Content-Type", "text/plain");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("HEAD");
    assertThat(recordedRequest.getHeader("User-Agent")).isEqualTo("SyncApiTest");
    assertThat(recordedRequest.getBody().size()).isEqualTo(0);
    assertThat(recordedRequest.getHeader("Content-Length")).isNull();
  }

  @Test public void headResponseContentLengthIsIgnored() throws Exception {
    server.enqueue(new MockResponse()
        .clearHeaders()
        .addHeader("Content-Length", "100"));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Request headRequest = new Request.Builder()
        .url(server.url("/").toString())
        .head()
        .build();
    Response response = client.newCall(headRequest).execute();
    assertThat(response.code()).isEqualTo(200);
    assertArrayEquals(new byte[0], response.body().bytes());

    Request getRequest = new Request.Builder()
        .url(server.url("/").toString())
        .build();
    executeSynchronously(getRequest)
        .assertCode(200)
        .assertBody("abc");

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @Test public void headResponseContentEncodingIsIgnored() throws Exception {
    server.enqueue(new MockResponse()
        .clearHeaders()
        .addHeader("Content-Encoding", "chunked"));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Request headRequest = new Request.Builder()
        .url(server.url("/").toString())
        .head()
        .build();
    executeSynchronously(headRequest)
        .assertCode(200)
        .assertHeader("Content-Encoding", "chunked")
        .assertBody("");

    Request getRequest = new Request.Builder()
        .url(server.url("/").toString())
        .build();
    executeSynchronously(getRequest)
        .assertCode(200)
        .assertBody("abc");

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @Test public void head_HTTPS() throws Exception {
    enableTls();
    head();
  }

  @Test public void post() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .post(RequestBody.create(MediaType.get("text/plain"), "def".getBytes(StandardCharsets.UTF_8)))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("POST");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("def");
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3");
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo("text/plain");
  }

  @Test public void post_HTTPS() throws Exception {
    enableTls();
    post();
  }

  @Test public void postZeroLength() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .method("POST", RequestBody.create(null, new byte[0]))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("POST");
    assertThat(recordedRequest.getBody().size()).isEqualTo(0);
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("0");
    assertThat(recordedRequest.getHeader("Content-Type")).isNull();
  }

  @Test public void postZerolength_HTTPS() throws Exception {
    enableTls();
    postZeroLength();
  }

  @Test public void delete() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .delete()
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("DELETE");
    assertThat(recordedRequest.getBody().size()).isEqualTo(0);
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("0");
    assertThat(recordedRequest.getHeader("Content-Type")).isNull();
  }

  @Test public void delete_HTTPS() throws Exception {
    enableTls();
    delete();
  }

  @Test public void deleteWithRequestBody() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .method("DELETE", RequestBody.create(MediaType.get("text/plain"), "def".getBytes(StandardCharsets.UTF_8)))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("DELETE");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("def");
  }

  @Test public void put() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .put(RequestBody.create(MediaType.get("text/plain"), "def".getBytes(StandardCharsets.UTF_8)))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("PUT");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("def");
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3");
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo("text/plain");
  }

  @Test public void put_HTTPS() throws Exception {
    enableTls();
    put();
  }

  @Test public void patch() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .patch(RequestBody.create(MediaType.get("text/plain"), "def".getBytes(StandardCharsets.UTF_8)))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("PATCH");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("def");
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3");
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo("text/plain");
  }

  @Test public void patch_HTTPS() throws Exception {
    enableTls();
    patch();
  }

  @Test public void customMethodWithBody() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .method("CUSTOM", RequestBody.create(MediaType.get("text/plain"), "def".getBytes(StandardCharsets.UTF_8)))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("CUSTOM");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("def");
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3");
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo("text/plain");
  }

  @Test public void unspecifiedRequestBodyContentTypeDoesNotGetDefault() throws Exception {
    server.enqueue(new MockResponse());

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .method("POST", RequestBody.create(null, "abc".getBytes(StandardCharsets.UTF_8)))
        .build();

    executeSynchronously(request).assertCode(200);

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getHeader("Content-Type")).isNull();
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("abc");
  }

  @Test public void illegalToExecuteTwice() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .header("User-Agent", "SyncApiTest")
        .build();

    Call call = client.newCall(request);
    Response response = call.execute();
    response.body().close();

    try {
      call.execute();
      fail();
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).isEqualTo("Already Executed");
    }

    try {
      call.execute();
      fail();
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).isEqualTo("Already Executed");
    }

    assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("SyncApiTest");
  }

  @Test public void connectionPooling() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.enqueue(new MockResponse().setBody("ghi"));

    executeSynchronously("/a").assertBody("abc");
    executeSynchronously("/b").assertBody("def");
    executeSynchronously("/c").assertBody("ghi");

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(2);
  }

  @Test public void timeoutsUpdatedOnReusedConnections() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def").throttleBody(1, 750, TimeUnit.MILLISECONDS));

    // First request: time out after 1000ms.
    client = client.newBuilder()
        .readTimeout(1000, TimeUnit.MILLISECONDS)
        .build();
    executeSynchronously("/a").assertBody("abc");

    // Second request: time out after 250ms.
    client = client.newBuilder()
        .readTimeout(250, TimeUnit.MILLISECONDS)
        .build();
    Request request = new Request.Builder().url(server.url("/b").toString()).build();
    Response response = client.newCall(request).execute();
    BufferedSource bodySource = response.body().source();
    assertThat(bodySource.readByte()).isEqualTo((byte) 'd');

    // The second byte of this request will be delayed by 750ms so we should time out after 250ms.
    long startNanos = System.nanoTime();
    try {
      bodySource.readByte();
      fail();
    } catch (IOException expected) {
      // Timed out as expected.
      long elapsedNanos = System.nanoTime() - startNanos;
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
      assertThat(elapsedMillis < 500).overridingErrorMessage(
          Util.format("Timed out: %sms", elapsedMillis)).isTrue();
    } finally {
      bodySource.close();
    }
  }

  /** https://github.com/square/okhttp/issues/442 */
  @Test public void tlsTimeoutsNotRetried() throws Exception {
    enableTls();
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.NO_RESPONSE));
    server.enqueue(new MockResponse()
        .setBody("unreachable!"));

    client = client.newBuilder()
        .readTimeout(100, TimeUnit.MILLISECONDS)
        .build();

    Request request = new Request.Builder().url(server.url("/").toString()).build();
    try {
      // If this succeeds, too many requests were made.
      client.newCall(request).execute();
      fail();
    } catch (InterruptedIOException expected) {
    }
  }

  /**
   * Make a request with two routes. The first route will time out because it's connecting to a
   * special address that never connects. The automatic retry will succeed.
   */
  @Test public void connectTimeoutsAttemptsAlternateRoute() throws Exception {
    RecordingProxySelector proxySelector = new RecordingProxySelector();
    proxySelector.proxies.add(new Proxy(Proxy.Type.HTTP, TestUtil.UNREACHABLE_ADDRESS));
    proxySelector.proxies.add(server.toProxyAddress());

    server.enqueue(new MockResponse()
        .setBody("success!"));

    client = client.newBuilder()
        .proxySelector(proxySelector)
        .readTimeout(100, TimeUnit.MILLISECONDS)
        .connectTimeout(100, TimeUnit.MILLISECONDS)
        .build();

    Request request = new Request.Builder().url("http://android.com/").build();
    executeSynchronously(request)
        .assertCode(200)
        .assertBody("success!");
  }

  /**
   * Make a request with two routes. The first route will fail because the null server connects but
   * never responds. The manual retry will succeed.
   */
  @Test public void readTimeoutFails() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.STALL_SOCKET_AT_START));
    server2.enqueue(new MockResponse()
        .setBody("success!"));

    RecordingProxySelector proxySelector = new RecordingProxySelector();
    proxySelector.proxies.add(server.toProxyAddress());
    proxySelector.proxies.add(server2.toProxyAddress());

    client = client.newBuilder()
        .proxySelector(proxySelector)
        .readTimeout(100, TimeUnit.MILLISECONDS)
        .build();

    Request request = new Request.Builder().url("http://android.com/").build();
    executeSynchronously(request)
        .assertFailure(SocketTimeoutException.class);
    executeSynchronously(request)
        .assertCode(200)
        .assertBody("success!");
  }

  @Test public void reusedSinksGetIndependentTimeoutInstances() throws Exception {
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    // Call 1: set a deadline on the request body.
    RequestBody requestBody1 = new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.get("text/plain");
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.writeUtf8("abc");
        sink.timeout().deadline(5, TimeUnit.SECONDS);
      }
    };
    Request request1 = new Request.Builder()
        .url(server.url("/").toString())
        .method("POST", requestBody1)
        .build();
    Response response1 = client.newCall(request1).execute();
    assertThat(response1.code()).isEqualTo(200);

    // Call 2: check for the absence of a deadline on the request body.
    RequestBody requestBody2 = new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.get("text/plain");
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        assertThat(sink.timeout().hasDeadline()).isFalse();
        sink.writeUtf8("def");
      }
    };
    Request request2 = new Request.Builder()
        .url(server.url("/").toString())
        .method("POST", requestBody2)
        .build();
    Response response2 = client.newCall(request2).execute();
    assertThat(response2.code()).isEqualTo(200);

    // Use sequence numbers to confirm the connection was pooled.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @Test public void reusedSourcesGetIndependentTimeoutInstances() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));

    // Call 1: set a deadline on the response body.
    Request request1 = new Request.Builder().url(server.url("/").toString()).build();
    Response response1 = client.newCall(request1).execute();
    BufferedSource body1 = response1.body().source();
    assertThat(body1.readUtf8()).isEqualTo("abc");
    body1.timeout().deadline(5, TimeUnit.SECONDS);

    // Call 2: check for the absence of a deadline on the request body.
    Request request2 = new Request.Builder().url(server.url("/").toString()).build();
    Response response2 = client.newCall(request2).execute();
    BufferedSource body2 = response2.body().source();
    assertThat(body2.readUtf8()).isEqualTo("def");
    assertThat(body2.timeout().hasDeadline()).isFalse();

    // Use sequence numbers to confirm the connection was pooled.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @Test public void tls() throws Exception {
    enableTls();
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));

    executeSynchronously("/").assertHandshake();
  }

  @Test public void noRecoverWhenRetryOnConnectionFailureIsFalse() throws Exception {
    server.enqueue(new MockResponse().setBody("seed connection pool"));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    server.enqueue(new MockResponse().setBody("unreachable!"));

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .retryOnConnectionFailure(false)
        .build();

    executeSynchronously("/").assertBody("seed connection pool");

    // If this succeeds, too many requests were made.
    executeSynchronously("/")
        .assertFailure(IOException.class)
        .assertFailureMatches("stream was reset: CANCEL",
            "unexpected end of stream on " + server.url("/").redact());
  }

  @Test public void tlsHandshakeFailure_noFallbackByDefault() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse().setBody("response that will never be received"));
    RecordedResponse response = executeSynchronously("/");
    response.assertFailure(
            SSLException.class, // JDK 11 response to the FAIL_HANDSHAKE
            SSLProtocolException.class // RI response to the FAIL_HANDSHAKE
    );
    assertThat(client.connectionSpecs().contains(ConnectionSpec.COMPATIBLE_TLS)).isFalse();
  }

  @Test public void recoverFromTlsHandshakeFailure() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse().setBody("abc"));

    client = client.newBuilder()
        .hostnameVerifier(new RecordingHostnameVerifier())
        // Attempt RESTRICTED_TLS then fall back to MODERN_TLS.
        .connectionSpecs(Arrays.asList(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
        .sslSocketFactory(
            suppressTlsFallbackClientSocketFactory(), handshakeCertificates.trustManager())
        .build();

    executeSynchronously("/").assertBody("abc");
  }

  @Test public void recoverFromTlsHandshakeFailure_tlsFallbackScsvEnabled() throws Exception {
    final String tlsFallbackScsv = "TLS_FALLBACK_SCSV";
    List<String> supportedCiphers =
        Arrays.asList(handshakeCertificates.sslSocketFactory().getSupportedCipherSuites());
    if (!supportedCiphers.contains(tlsFallbackScsv)) {
      // This only works if the client socket supports TLS_FALLBACK_SCSV.
      return;
    }

    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));

    RecordingSSLSocketFactory clientSocketFactory =
        new RecordingSSLSocketFactory(handshakeCertificates.sslSocketFactory());
    client = client.newBuilder()
        .sslSocketFactory(clientSocketFactory, handshakeCertificates.trustManager())
        // Attempt RESTRICTED_TLS then fall back to MODERN_TLS.
        .connectionSpecs(Arrays.asList(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    Request request = new Request.Builder().url(server.url("/").toString()).build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (SSLHandshakeException expected) {
    }

    List<SSLSocket> clientSockets = clientSocketFactory.getSocketsCreated();
    SSLSocket firstSocket = clientSockets.get(0);
    assertThat(Arrays.asList(firstSocket.getEnabledCipherSuites()).contains(tlsFallbackScsv)).isFalse();
    SSLSocket secondSocket = clientSockets.get(1);
    assertThat(Arrays.asList(secondSocket.getEnabledCipherSuites()).contains(tlsFallbackScsv)).isTrue();
  }

  @Test public void noRecoveryFromTlsHandshakeFailureWhenTlsFallbackIsDisabled() throws Exception {
    client = client.newBuilder()
        .connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
        .hostnameVerifier(new RecordingHostnameVerifier())
        .sslSocketFactory(
            suppressTlsFallbackClientSocketFactory(), handshakeCertificates.trustManager())
        .build();

    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));

    Request request = new Request.Builder().url(server.url("/").toString()).build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (SSLProtocolException expected) {
      // RI response to the FAIL_HANDSHAKE
    } catch (SSLException expected) {
      // JDK 11 response to the FAIL_HANDSHAKE
      String jvmVersion = System.getProperty("java.specification.version");
      assertThat(jvmVersion).isEqualTo("11");
    }
  }

  @Test public void tlsHostnameVerificationFailure() throws Exception {
    server.enqueue(new MockResponse());

    HeldCertificate serverCertificate = new HeldCertificate.Builder()
        .commonName("localhost") // Unusued for hostname verification.
        .addSubjectAlternativeName("wronghostname")
        .build();

    HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
        .heldCertificate(serverCertificate)
        .build();

    HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
        .addTrustedCertificate(serverCertificate.certificate())
        .build();

    client = client.newBuilder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager())
        .build();
    server.useHttps(serverCertificates.sslSocketFactory(), false);

    executeSynchronously("/")
        .assertFailureMatches("(?s)Hostname localhost not verified.*");
  }

  /**
   * Anonymous cipher suites were disabled in OpenJDK because they're rarely used and permit
   * man-in-the-middle attacks. https://bugs.openjdk.java.net/browse/JDK-8212823
   */
  @Test public void anonCipherSuiteUnsupported() throws Exception {
    // The _anon_ suites became unsupported in "1.8.0_201" and "11.0.2".
    assumeFalse(System.getProperty("java.version", "unknown").matches("1\\.8\\.0_1\\d\\d"));
    assumeFalse(System.getProperty("java.version", "unknown").matches("9\\..*"));
    assumeFalse(System.getProperty("java.version", "unknown").matches("11\\..*"));

    server.enqueue(new MockResponse());

    CipherSuite cipherSuite = CipherSuite.TLS_DH_anon_WITH_AES_128_GCM_SHA256;

    HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
        .build();
    client = client.newBuilder()
        .sslSocketFactory(
            socketFactoryWithCipherSuite(clientCertificates.sslSocketFactory(), cipherSuite),
            clientCertificates.trustManager())
        .connectionSpecs(Arrays.asList(new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .cipherSuites(cipherSuite)
            .build()))
        .build();

    HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
        .build();
    server.useHttps(socketFactoryWithCipherSuite(
        serverCertificates.sslSocketFactory(), cipherSuite), false);

    executeSynchronously("/")
        .assertFailure(SSLHandshakeException.class);
  }

  @Test public void cleartextCallsFailWhenCleartextIsDisabled() throws Exception {
    // Configure the client with only TLS configurations. No cleartext!
    client = client.newBuilder()
        .connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .build();

    server.enqueue(new MockResponse());

    Request request = new Request.Builder().url(server.url("/").toString()).build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (UnknownServiceException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          "CLEARTEXT communication not enabled for client");
    }
  }

  @Test public void postBodyRetransmittedOnFailureRecovery() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    server.enqueue(new MockResponse().setBody("def"));

    // Seed the connection pool so we have something that can fail.
    Request request1 = new Request.Builder().url(server.url("/").toString()).build();
    Response response1 = client.newCall(request1).execute();
    assertThat(response1.body().bytes()).isEqualTo("abc".getBytes(StandardCharsets.UTF_8));

    Request request2 = new Request.Builder()
        .url(server.url("/").toString())
        .post(RequestBody.create(MediaType.get("text/plain"), "body!".getBytes(StandardCharsets.UTF_8)))
        .build();
    Response response2 = client.newCall(request2).execute();
    assertThat(response2.body().bytes()).isEqualTo("def".getBytes(StandardCharsets.UTF_8));

    RecordedRequest get = server.takeRequest();
    assertThat(get.getSequenceNumber()).isEqualTo(0);

    RecordedRequest post1 = server.takeRequest();
    assertThat(post1.getBody().readUtf8()).isEqualTo("body!");
    assertThat(post1.getSequenceNumber()).isEqualTo(1);

    RecordedRequest post2 = server.takeRequest();
    assertThat(post2.getBody().readUtf8()).isEqualTo("body!");
    assertThat(post2.getSequenceNumber()).isEqualTo(0);
  }

  @Test public void responseCookies() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Set-Cookie", "a=b; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
        .addHeader("Set-Cookie", "c=d; Expires=Fri, 02 Jan 1970 23:59:59 GMT; path=/bar; secure"));

    RecordingCookieJar cookieJar = new RecordingCookieJar();
    client = client.newBuilder()
        .cookieJar(cookieJar)
        .build();

    executeSynchronously("/").assertCode(200);

    List<Cookie> responseCookies = cookieJar.takeResponseCookies();
    assertThat(responseCookies.size()).isEqualTo(2);
    assertThat(responseCookies.get(0).toString()).isEqualTo(
        "a=b; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/");
    assertThat(responseCookies.get(1).toString()).isEqualTo(
        "c=d; expires=Fri, 02 Jan 1970 23:59:59 GMT; path=/bar; secure");
  }

  @Test public void requestCookies() throws Exception {
    server.enqueue(new MockResponse());

    RecordingCookieJar cookieJar = new RecordingCookieJar();

    cookieJar.enqueueRequestCookies(
        new Cookie.Builder().name("a").value("b").domain(server.getHostName()).build(),
        new Cookie.Builder().name("c").value("d").domain(server.getHostName()).build());
    client = client.newBuilder()
        .cookieJar(cookieJar)
        .build();

    executeSynchronously("/").assertCode(200);

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getHeader("Cookie")).isEqualTo("a=b; c=d");
  }

  @Test public void httpWithExcessiveHeaders() throws IOException {
    String longLine = "HTTP/1.1 200 " + stringFill('O', 256 * 1024) + "K";

    server.setProtocols(Collections.singletonList(okhttp3.Protocol.HTTP_1_1));

    server.enqueue(new MockResponse()
        .setStatus(longLine)
        .setBody("I'm not even supposed to be here today."));

    executeSynchronously("/")
        .assertFailureMatches(".*unexpected end of stream on " + server.url("/").redact());
  }

  private String stringFill(char fillChar, int length) {
    char[] value = new char[length];
    Arrays.fill(value, fillChar);
    return new String(value);
  }

  @Test public void canceledBeforeExecute() throws Exception {
    Call call = client.newCall(new Request.Builder().url(server.url("/a").toString()).build());
    call.cancel();

    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }
    assertThat(server.getRequestCount()).isEqualTo(0);
  }

  @Test public void cancelDuringHttpConnect() throws Exception {
    cancelDuringConnect("http");
  }

  @Test public void cancelDuringHttpsConnect() throws Exception {
    cancelDuringConnect("https");
  }

  /** Cancel a call that's waiting for connect to complete. */
  private void cancelDuringConnect(String scheme) throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.STALL_SOCKET_AT_START));

    long cancelDelayMillis = 300L;
    Call call = client.newCall(new Request.Builder()
        .url(Url.get(server.url("/").toString()).newBuilder().scheme(scheme).build())
        .build());
    cancelLater(call, cancelDelayMillis);

    long startNanos = System.nanoTime();
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    assertThat((float) TimeUnit.NANOSECONDS.toMillis(elapsedNanos)).isCloseTo(
        (float) cancelDelayMillis, offset(100f));
  }

  @Test public void cancelBeforeBodyIsRead() throws Exception {
    server.enqueue(new MockResponse().setBody("def").throttleBody(1, 750, TimeUnit.MILLISECONDS));

    final Call call = client.newCall(new Request.Builder().url(server.url("/a").toString()).build());
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<Response> result = executor.submit(call::execute);

    Thread.sleep(100); // wait for it to go in flight.

    call.cancel();
    try {
      result.get().body().bytes();
      fail();
    } catch (IOException expected) {
    }
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test public void cancelInFlightBeforeResponseReadThrowsIOE() throws Exception {
    Request request = new Request.Builder().url(server.url("/a").toString()).build();
    final Call call = client.newCall(request);

    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) {
        call.cancel();
        return new MockResponse().setBody("A");
      }
    });

    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void cancelInFlightBeforeResponseReadThrowsIOE_HTTPS() throws Exception {
    enableTls();
    cancelInFlightBeforeResponseReadThrowsIOE();
  }

  @Test public void gzip() throws Exception {
    okio.Buffer gzippedBody = gzip("abcabcabc");

    server.enqueue(new MockResponse()
        .setBody(gzippedBody)
        .addHeader("Content-Encoding: gzip"));

    // Confirm that the user request doesn't have Accept-Encoding, and the user
    // response doesn't have a Content-Encoding or Content-Length.
    RecordedResponse userResponse = executeSynchronously("/");
    userResponse.assertCode(200)
        .assertRequestHeader("Accept-Encoding")
        .assertHeader("Content-Encoding")
        .assertHeader("Content-Length")
        .assertBody("abcabcabc");
  }

  @Test public void rangeHeaderPreventsAutomaticGzip() throws Exception {
    okio.Buffer gzippedBody = gzip("abcabcabc");

    // Enqueue a gzipped response. Our request isn't expecting it, but that's okay.
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_PARTIAL)
        .setBody(gzippedBody)
        .addHeader("Content-Encoding: gzip")
        .addHeader("Content-Range: bytes 0-" + (gzippedBody.size() - 1)));

    // Make a range request.
    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .header("Range", "bytes=0-")
        .build();
    Call call = client.newCall(request);

    // The response is not decompressed.
    Response response = call.execute();
    assertThat(response.header("Content-Encoding")).isEqualTo("gzip");
    assertThat(response.body().source().readByteArray()).isEqualTo(gzippedBody.snapshot().toByteArray());

    // The request did not offer gzip support.
    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getHeader("Accept-Encoding")).isNull();
  }

  @Test public void userAgentIsIncludedByDefault() throws Exception {
    server.enqueue(new MockResponse());

    executeSynchronously("/");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getHeader("User-Agent")
        .matches(Version.userAgent())).isTrue();
  }

  @Test public void serverRespondsWithUnsolicited100Continue() throws Exception {
    client = client.newBuilder()
        .readTimeout(1, TimeUnit.SECONDS)
        .build();

    server.enqueue(new MockResponse()
        .setStatus("HTTP/1.1 100 Continue"));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .post(RequestBody.create(MediaType.get("text/plain"), "abc".getBytes(StandardCharsets.UTF_8)))
        .build();

    client.newCall(request).execute();

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("abc");
  }

  /** We forbid non-ASCII characters in outgoing request headers, but accept UTF-8. */
  @Test public void responseHeaderParsingIsLenient() throws Exception {
    okhttp3.Headers.Builder headersBuilder = new okhttp3.Headers.Builder();
    headersBuilder.add("Content-Length", "0");
    Internal.addHeaderLenient(headersBuilder, "a\tb: c\u007fd");
    Internal.addHeaderLenient(headersBuilder, ": ef");
    Internal.addHeaderLenient(headersBuilder, "\ud83c\udf69: \u2615\ufe0f");
    server.enqueue(new MockResponse().setHeaders(headersBuilder.build()));

    executeSynchronously("/")
        .assertHeader("a\tb", "c\u007fd")
        .assertHeader("\ud83c\udf69", "\u2615\ufe0f")
        .assertHeader("", "ef");
  }

  @Test public void customDns() throws Exception {
    // Configure a DNS that returns our local MockWebServer for android.com.
    FakeDns dns = new FakeDns();
    dns.set("android.com", Dns.SYSTEM.lookup(server.url("/").host()));
    client = client.newBuilder()
        .dns(dns)
        .build();

    server.enqueue(new MockResponse());
    Request request = new Request.Builder()
        .url(Url.get(server.url("/").toString()).newBuilder().host("android.com").build())
        .build();
    executeSynchronously(request).assertCode(200);

    dns.assertRequests("android.com");
  }
  @Test public void dnsReturnsZeroIpAddresses() throws Exception {
    // Configure a DNS that returns our local MockWebServer for android.com.
    FakeDns dns = new FakeDns();
    List<InetAddress> ipAddresses = new ArrayList<>();
    dns.set("android.com", ipAddresses);
    client = client.newBuilder()
        .dns(dns)
        .build();

    server.enqueue(new MockResponse());
    Request request = new Request.Builder()
        .url(Url.get(server.url("/").toString()).newBuilder().host("android.com").build())
        .build();
    executeSynchronously(request).assertFailure(dns + " returned no addresses for android.com");

    dns.assertRequests("android.com");
  }

  /** Test which headers are sent unencrypted to the HTTP proxy. */
  @Test public void proxyConnectOmitsApplicationHeaders() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("encrypted response from the origin server"));

    RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .proxy(server.toProxyAddress())
        .hostnameVerifier(hostnameVerifier)
        .build();

    Request request = new Request.Builder()
        .url("https://android.com/foo")
        .header("Private", "Secret")
        .header("User-Agent", "App 1.0")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().bytes()).isEqualTo(
        "encrypted response from the origin server".getBytes(StandardCharsets.UTF_8));

    RecordedRequest connect = server.takeRequest();
    assertThat(connect.getHeader("Private")).isNull();
    assertThat(connect.getHeader("User-Agent")).isEqualTo(Version.userAgent());
    assertThat(connect.getHeader("Proxy-Connection")).isEqualTo("Keep-Alive");
    assertThat(connect.getHeader("Host")).isEqualTo("android.com:443");

    RecordedRequest get = server.takeRequest();
    assertThat(get.getHeader("Private")).isEqualTo("Secret");
    assertThat(get.getHeader("User-Agent")).isEqualTo("App 1.0");

    assertThat(hostnameVerifier.calls).isEqualTo(Arrays.asList("verify android.com"));
  }

  /**
   * Confirm that we don't send the Proxy-Authorization header from the request to the proxy server.
   * We used to have that behavior but it is problematic because unrelated requests end up sharing
   * credentials. Worse, that approach leaks proxy credentials to the origin server.
   */
  @Test public void noPreemptiveProxyAuthorization() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("response body"));

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .proxy(server.toProxyAddress())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    Request request = new Request.Builder()
        .url("https://android.com/foo")
        .header("Proxy-Authorization", "password")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().bytes()).isEqualTo("response body".getBytes(StandardCharsets.UTF_8));

    RecordedRequest connect1 = server.takeRequest();
    assertThat(connect1.getHeader("Proxy-Authorization")).isNull();

    RecordedRequest connect2 = server.takeRequest();
    assertThat(connect2.getHeader("Proxy-Authorization")).isEqualTo("password");
  }

  @Test public void serverSendsInvalidResponseHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .setStatus("HTP/1.1 200 OK"));

    executeSynchronously("/")
        .assertFailure("Unexpected status line: HTP/1.1 200 OK");
  }

  @Test public void serverSendsInvalidCodeTooLarge() throws Exception {
    server.enqueue(new MockResponse()
        .setStatus("HTTP/1.1 2147483648 OK"));

    executeSynchronously("/")
        .assertFailure("Unexpected status line: HTTP/1.1 2147483648 OK");
  }

  @Test public void serverSendsInvalidCodeNotANumber() throws Exception {
    server.enqueue(new MockResponse()
        .setStatus("HTTP/1.1 00a OK"));

    executeSynchronously("/")
        .assertFailure("Unexpected status line: HTTP/1.1 00a OK");
  }

  @Test public void serverSendsUnnecessaryWhitespace() throws Exception {
    server.enqueue(new MockResponse()
        .setStatus(" HTTP/1.1 200 OK"));

    executeSynchronously("/")
        .assertFailure("Unexpected status line:  HTTP/1.1 200 OK");
  }

  @Test public void requestHeaderNameWithSpaceForbidden() throws Exception {
    try {
      new Request.Builder().addHeader("a b", "c");
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          "Unexpected char 0x20 at 1 in header name: a b");
    }
  }

  @Test public void requestHeaderNameWithTabForbidden() throws Exception {
    try {
      new Request.Builder().addHeader("a\tb", "c");
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          "Unexpected char 0x09 at 1 in header name: a\tb");
    }
  }

  @Test public void responseHeaderNameWithSpacePermitted() throws Exception {
    server.enqueue(new MockResponse()
        .clearHeaders()
        .addHeader("content-length: 0")
        .addHeaderLenient("a b", "c"));

    Call call = client.newCall(new Request.Builder().url(server.url("/").toString()).build());
    Response response = call.execute();
    assertThat(response.header("a b")).isEqualTo("c");
  }

  @Test public void responseHeaderNameWithTabPermitted() throws Exception {
    server.enqueue(new MockResponse()
        .clearHeaders()
        .addHeader("content-length: 0")
        .addHeaderLenient("a\tb", "c"));

    Call call = client.newCall(new Request.Builder().url(server.url("/").toString()).build());
    Response response = call.execute();
    assertThat(response.header("a\tb")).isEqualTo("c");
  }

  @Test public void connectFails() throws Exception {
    server.shutdown();

    executeSynchronously("/")
        .assertFailure(IOException.class);
  }

  @Test public void requestBodySurvivesRetries() throws Exception {
    server.enqueue(new MockResponse());

    // Enable a misconfigured proxy selector to guarantee that the request is retried.
    client = client.newBuilder()
        .proxySelector(new FakeProxySelector()
            .addProxy(server2.toProxyAddress())
            .addProxy(Proxy.NO_PROXY))
        .build();
    server2.shutdown();

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .post(RequestBody.create(MediaType.get("text/plain"), "abc".getBytes(StandardCharsets.UTF_8)))
        .build();

    executeSynchronously(request);
    assertThat(server.takeRequest().getBody().readUtf8()).isEqualTo("abc");
  }

  @Ignore // This may fail in DNS lookup, which we don't have timeouts for.
  @Test public void invalidHost() throws Exception {
    Request request = new Request.Builder()
        .url(Url.get("http://1234.1.1.1/"))
        .build();

    executeSynchronously(request)
        .assertFailure(UnknownHostException.class);
  }

  @Test public void uploadBodySmallChunkedEncoding() throws Exception {
    upload(true, 1048576, 256);
    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBodySize()).isEqualTo(1048576);
    assertThat(recordedRequest.getChunkSizes().isEmpty()).isFalse();
  }

  @Test public void uploadBodyLargeChunkedEncoding() throws Exception {
    upload(true, 1048576, 65536);
    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBodySize()).isEqualTo(1048576);
    assertThat(recordedRequest.getChunkSizes().isEmpty()).isFalse();
  }

  @Test public void uploadBodySmallFixedLength() throws Exception {
    upload(false, 1048576, 256);
    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBodySize()).isEqualTo(1048576);
    assertThat(recordedRequest.getChunkSizes().isEmpty()).isTrue();
  }

  @Test public void uploadBodyLargeFixedLength() throws Exception {
    upload(false, 1048576, 65536);
    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBodySize()).isEqualTo(1048576);
    assertThat(recordedRequest.getChunkSizes().isEmpty()).isTrue();
  }

  private void upload(
      final boolean chunked, final int size, final int writeSize) throws Exception {
    server.enqueue(new MockResponse());
    executeSynchronously(new Request.Builder()
        .url(server.url("/").toString())
        .post(requestBody(chunked, size, writeSize))
        .build());
  }

  /** https://github.com/square/okhttp/issues/2344 */
  @Test public void ipv6HostHasSquareBraces() throws Exception {
    // Use a proxy to fake IPv6 connectivity, even if localhost doesn't have IPv6.
    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
    server.setProtocols(Collections.singletonList(okhttp3.Protocol.HTTP_1_1));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("response body"));

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .proxy(server.toProxyAddress())
        .build();

    Request request = new Request.Builder()
        .url("https://[::1]/")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().bytes()).isEqualTo("response body".getBytes(StandardCharsets.UTF_8));

    RecordedRequest connect = server.takeRequest();
    assertThat(connect.getRequestLine()).isEqualTo("CONNECT [::1]:443 HTTP/1.1");
    assertThat(connect.getHeader("Host")).isEqualTo("[::1]:443");

    RecordedRequest get = server.takeRequest();
    assertThat(get.getRequestLine()).isEqualTo("GET / HTTP/1.1");
    assertThat(get.getHeader("Host")).isEqualTo("[::1]");
  }

  private RequestBody requestBody(final boolean chunked, final long size, final int writeSize) {
    final byte[] buffer = new byte[writeSize];
    Arrays.fill(buffer, (byte) 'x');

    return new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.get("text/plain; charset=utf-8");
      }

      @Override public long contentLength() throws IOException {
        return chunked ? -1L : size;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        for (int count = 0; count < size; count += writeSize) {
          sink.write(buffer, 0, (int) Math.min(size - count, writeSize));
        }
      }
    };
  }

  @Test public void emptyResponseBody() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("abc", "def"));
    executeSynchronously("/")
        .assertCode(200)
        .assertHeader("abc", "def")
        .assertBody("");
  }

  @Test public void leakedResponseBodyLogsStackTrace() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("This gets leaked."));

    client = clientTestRule.client.newBuilder()
        .connectionPool(new ConnectionPool(0, 10, TimeUnit.MILLISECONDS))
        .build();

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .build();

    Level original = logger.getLevel();
    logger.setLevel(Level.FINE);
    logHandler.setFormatter(new SimpleFormatter());
    try {
      client.newCall(request).execute(); // Ignore the response so it gets leaked then GC'd.
      TestUtil.awaitGarbageCollection();

      String message = logHandler.take();
      assertThat(message.contains("A connection to " + server.url("/") + " was leaked."
            + " Did you forget to close a response body?")).isTrue();
      assertThat(message.contains("linktiger.http.Call.execute(")).isTrue();
      assertThat(message.contains("linktiger.http.CallTest.leakedResponseBodyLogsStackTrace(")).isTrue();
    } finally {
      logger.setLevel(original);
    }
  }

  @Test public void httpsWithIpAddress() throws Exception {
    String localIpAddress = InetAddress.getLoopbackAddress().getHostAddress();

    // Create a certificate with an IP address in the subject alt name.
    HeldCertificate heldCertificate = new HeldCertificate.Builder()
        .commonName("example.com")
        .addSubjectAlternativeName(localIpAddress)
        .build();
    HandshakeCertificates handshakeCertificates = new HandshakeCertificates.Builder()
        .heldCertificate(heldCertificate)
        .addTrustedCertificate(heldCertificate.certificate())
        .build();

    // Use that certificate on the server and trust it on the client.
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    // Make a request.
    server.enqueue(new MockResponse());
    Url url = Url.get(server.url("/").toString()).newBuilder()
        .host(localIpAddress)
        .build();
    Request request = new Request.Builder()
        .url(url)
        .build();
    executeSynchronously(request)
        .assertCode(200);

    // Confirm that the IP address was used in the host header.
    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getHeader("Host")).isEqualTo(
        (localIpAddress + ":" + server.getPort()));
  }

  @Test public void clientReadsHeadersDataTrailersHttp1ChunkedTransferEncoding() throws Exception {
    MockResponse mockResponse = new MockResponse()
        .clearHeaders()
        .addHeader("h1", "v1")
        .addHeader("h2", "v2")
        .setChunkedBody("HelloBonjour", 1024)
        .setTrailers(okhttp3.Headers.of("trailers", "boom"));
    server.enqueue(mockResponse);

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());

    Response response = call.execute();
    BufferedSource source = response.body().source();

    assertThat(response.header("h1")).isEqualTo("v1");
    assertThat(response.header("h2")).isEqualTo("v2");

    assertThat(source.readUtf8(5)).isEqualTo("Hello");
    assertThat(source.readUtf8(7)).isEqualTo("Bonjour");

    assertThat(source.exhausted()).isTrue();
    assertThat(response.trailers()).isEqualTo(Headers.of("trailers", "boom"));
  }

  @Test public void requestBodyThrowsUnrelatedToNetwork() throws Exception {
    server.enqueue(new MockResponse());

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .post(new RequestBody() {
          @Override public MediaType contentType() {
            return null;
          }

          @Override public void writeTo(BufferedSink sink) throws IOException {
            throw new IOException("boom");
          }
        })
        .build();

    executeSynchronously(request).assertFailure("boom");
  }

  private void makeFailingCall() {
    RequestBody requestBody = new RequestBody() {
      @Override public MediaType contentType() {
        return null;
      }

      @Override public long contentLength() throws IOException {
        return 1;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        throw new IOException("write body fail!");
      }
    };
    HttpClient nonRetryingClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .build();
    Call call = nonRetryingClient.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .post(requestBody)
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage()).isEqualTo("write body fail!");
    }
  }

  private RecordedResponse executeSynchronously(String path, String... headers) throws IOException {
    Request.Builder builder = new Request.Builder();
    builder.url(server.url(path).toString());
    for (int i = 0, size = headers.length; i < size; i += 2) {
      builder.addHeader(headers[i], headers[i + 1]);
    }
    return executeSynchronously(builder.build());
  }

  private RecordedResponse executeSynchronously(Request request) throws IOException {
    Call call = client.newCall(request);
    try {
      Response response = call.execute();
      return new RecordedResponse(request, response, null);
    } catch (IOException e) {
      return new RecordedResponse(request, null, e);
    }
  }

  private void enableTls() {
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
  }

  private okio.Buffer gzip(String data) throws IOException {
    okio.Buffer result = new okio.Buffer();
    okio.BufferedSink sink = okio.Okio.buffer(new okio.GzipSink(result));
    sink.writeUtf8(data);
    sink.close();
    return result;
  }

  private Thread cancelLater(final Call call, final long delay) {
    Thread thread = new Thread("canceler") {
      @Override public void run() {
        try {
          Thread.sleep(delay);
        } catch (InterruptedException e) {
          throw new AssertionError();
        }
        call.cancel();
      }
    };
    thread.start();
    return thread;
  }

  private SSLSocketFactory socketFactoryWithCipherSuite(
      final SSLSocketFactory sslSocketFactory, final CipherSuite cipherSuite) {
    return new DelegatingSSLSocketFactory(sslSocketFactory) {
      @Override protected SSLSocket configureSocket(SSLSocket sslSocket) throws IOException {
        sslSocket.setEnabledCipherSuites(new String[] { cipherSuite.javaName() });
        return super.configureSocket(sslSocket);
      }
    };
  }

  private static class RecordingSSLSocketFactory extends DelegatingSSLSocketFactory {

    private List<SSLSocket> socketsCreated = new ArrayList<>();

    public RecordingSSLSocketFactory(SSLSocketFactory delegate) {
      super(delegate);
    }

    @Override
    protected SSLSocket configureSocket(SSLSocket sslSocket) throws IOException {
      socketsCreated.add(sslSocket);
      return sslSocket;
    }

    public List<SSLSocket> getSocketsCreated() {
      return socketsCreated;
    }
  }

  /**
   * Used during tests that involve TLS connection fallback attempts. OkHttp includes the
   * TLS_FALLBACK_SCSV cipher on fallback connections. See {@link FallbackTestClientSocketFactory}
   * for details.
   */
  private FallbackTestClientSocketFactory suppressTlsFallbackClientSocketFactory() {
    return new FallbackTestClientSocketFactory(handshakeCertificates.sslSocketFactory());
  }
}
