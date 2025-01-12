/*
 * Copyright (C) 2017 Square, Inc.
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import linktiger.http.RecordingEventListener.CallEnd;
import linktiger.http.RecordingEventListener.CallFailed;
import linktiger.http.RecordingEventListener.ConnectEnd;
import linktiger.http.RecordingEventListener.ConnectFailed;
import linktiger.http.RecordingEventListener.ConnectStart;
import linktiger.http.RecordingEventListener.ConnectionAcquired;
import linktiger.http.RecordingEventListener.DnsEnd;
import linktiger.http.RecordingEventListener.DnsStart;
import linktiger.http.RecordingEventListener.RequestBodyEnd;
import linktiger.http.RecordingEventListener.RequestHeadersEnd;
import linktiger.http.RecordingEventListener.ResponseBodyEnd;
import linktiger.http.RecordingEventListener.ResponseFailed;
import linktiger.http.RecordingEventListener.ResponseHeadersEnd;
import linktiger.http.RecordingEventListener.SecureConnectEnd;
import linktiger.http.RecordingEventListener.SecureConnectStart;
import linktiger.http.impl.DoubleInetAddressDns;
import linktiger.http.impl.RecordingOkAuthenticator;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import okio.Buffer;
import okio.BufferedSink;
import org.assertj.core.api.Assertions;
import org.hamcrest.BaseMatcher;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static java.util.Arrays.asList;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.hamcrest.CoreMatchers.any;
import static org.hamcrest.CoreMatchers.either;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

public final class EventListenerTest {
  public static final Matcher<Response> anyResponse = CoreMatchers.any(Response.class);
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final HttpClientTestRule clientTestRule = new HttpClientTestRule();

  private final RecordingEventListener listener = new RecordingEventListener();
  private final HandshakeCertificates handshakeCertificates = localhost();

  private HttpClient client = clientTestRule.client;
  private SocksProxy socksProxy;

  @Before public void setUp() {
    client = clientTestRule.client.newBuilder()
        .eventListener(listener)
        .build();

    listener.forbidLock(client.connectionPool());
  }

  @After public void tearDown() throws Exception {
    if (socksProxy != null) {
      socksProxy.shutdown();
    }
  }

  @Test public void successfulCallEventSequence() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    Assertions.assertThat(response.body().string()).isEqualTo("abc");
    response.body().close();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  @Test public void failedCallEventSequence() {
    server.enqueue(new MockResponse().setHeadersDelay(2, TimeUnit.SECONDS));

    client = client.newBuilder().readTimeout(250, TimeUnit.MILLISECONDS).build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage(), either(equalTo("timeout")).or(equalTo("Read timed out")));
    }

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseFailed", "ConnectionReleased",
        "CallFailed");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  @Test public void failedDribbledCallEventSequence() throws IOException {
    server.enqueue(new MockResponse().setBody("0123456789")
        .throttleBody(2, 100, TimeUnit.MILLISECONDS)
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

    client = client.newBuilder()
        .protocols(Collections.singletonList(Protocol.HTTP_1_1))
        .readTimeout(250, TimeUnit.MILLISECONDS)
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());

    Response response = call.execute();
    try {
      response.body().string();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage(), equalTo("unexpected end of stream"));
    }

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseFailed", "ConnectionReleased", "CallFailed");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
    ResponseFailed responseFailed = listener.removeUpToEvent(ResponseFailed.class);
    Assertions.assertThat(responseFailed.ioe.getMessage()).isEqualTo(
        "unexpected end of stream");
  }

  @Test public void canceledCallEventSequence() {
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    call.cancel();
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
      Assertions.assertThat(expected.getMessage()).isEqualTo("Canceled");
    }

    List<String> expectedEvents = Arrays.asList("CallStart", "CallFailed");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  private void assertSuccessfulEventOrder(Matcher<Response> responseMatcher) throws IOException {
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().string();
    response.body().close();

    assumeThat(response, responseMatcher);

    List<String> expectedEvents = asList("CallStart", "DnsStart", "DnsEnd", "ConnectStart",
        "SecureConnectStart", "SecureConnectEnd", "ConnectEnd", "ConnectionAcquired",
        "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd",
        "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased", "CallEnd");

    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  @Test public void secondCallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(List.of(okhttp3.Protocol.HTTP_1_1));
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build()).execute().close();

    listener.removeUpToEvent(CallEnd.class);

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response = call.execute();
    response.close();

    List<String> expectedEvents = asList("CallStart", "ConnectionAcquired",
        "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd",
        "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased", "CallEnd");

    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  private void assertBytesReadWritten(RecordingEventListener listener,
      Matcher<Long> requestHeaderLength, Matcher<Long> requestBodyBytes,
      Matcher<Long> responseHeaderLength, Matcher<Long> responseBodyBytes) {

    if (requestHeaderLength != null) {
      RequestHeadersEnd responseHeadersEnd = listener.removeUpToEvent(RequestHeadersEnd.class);
      assertThat("request header length", responseHeadersEnd.headerLength, requestHeaderLength);
    } else {
      Assertions.assertThat(listener.recordedEventTypes().contains("RequestHeadersEnd")).overridingErrorMessage(
          "Found RequestHeadersEnd").isFalse();
    }

    if (requestBodyBytes != null) {
      RequestBodyEnd responseBodyEnd = listener.removeUpToEvent(RequestBodyEnd.class);
      assertThat("request body bytes", responseBodyEnd.bytesWritten, requestBodyBytes);
    } else {
      Assertions.assertThat(listener.recordedEventTypes().contains("RequestBodyEnd")).overridingErrorMessage(
          "Found RequestBodyEnd").isFalse();
    }

    if (responseHeaderLength != null) {
      ResponseHeadersEnd responseHeadersEnd = listener.removeUpToEvent(ResponseHeadersEnd.class);
      assertThat("response header length", responseHeadersEnd.headerLength, responseHeaderLength);
    } else {
      Assertions.assertThat(listener.recordedEventTypes().contains("ResponseHeadersEnd")).overridingErrorMessage(
          "Found ResponseHeadersEnd").isFalse();
    }

    if (responseBodyBytes != null) {
      ResponseBodyEnd responseBodyEnd = listener.removeUpToEvent(ResponseBodyEnd.class);
      assertThat("response body bytes", responseBodyEnd.bytesRead, responseBodyBytes);
    } else {
      Assertions.assertThat(listener.recordedEventTypes().contains("ResponseBodyEnd")).overridingErrorMessage(
          "Found ResponseBodyEnd").isFalse();
    }
  }

  private Matcher<Long> greaterThan(final long value) {
    return new BaseMatcher<Long>() {
      @Override public void describeTo(Description description) {
        description.appendText("> " + value);
      }

      @Override public boolean matches(Object o) {
        return ((Long)o) > value;
      }
    };
  }

  private Matcher<Long> lessThan(final long value) {
    return new BaseMatcher<Long>() {
      @Override public void describeTo(Description description) {
        description.appendText("< " + value);
      }

      @Override public boolean matches(Object o) {
        return ((Long)o) < value;
      }
    };
  }

  @Test public void successfulEmptyHttpsCallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(okhttp3.Protocol.HTTP_1_1));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    assertSuccessfulEventOrder(anyResponse);

    assertBytesReadWritten(listener, any(Long.class), null, greaterThan(0L),
        equalTo(3L));
  }

  @Test public void successfulChunkedHttpsCallEventSequence() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(okhttp3.Protocol.HTTP_1_1));
    server.enqueue(
        new MockResponse().setBodyDelay(100, TimeUnit.MILLISECONDS).setChunkedBody("Hello!", 2));

    assertSuccessfulEventOrder(anyResponse);

    assertBytesReadWritten(listener, any(Long.class), null, greaterThan(0L),
        equalTo(6L));
  }

  @Test public void successfulDnsLookup() throws IOException {
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    DnsStart dnsStart = listener.removeUpToEvent(DnsStart.class);
    Assertions.assertThat(dnsStart.call).isSameAs(call);
    Assertions.assertThat(dnsStart.domainName).isEqualTo(server.getHostName());

    DnsEnd dnsEnd = listener.removeUpToEvent(DnsEnd.class);
    Assertions.assertThat(dnsEnd.call).isSameAs(call);
    Assertions.assertThat(dnsEnd.domainName).isEqualTo(server.getHostName());
    Assertions.assertThat(dnsEnd.inetAddressList.size()).isEqualTo(1);
  }

  @Test public void noDnsLookupOnPooledConnection() throws IOException {
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    // Seed the pool.
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response1 = call1.execute();
    Assertions.assertThat(response1.code()).isEqualTo(200);
    response1.body().close();

    listener.clearAllEvents();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response2 = call2.execute();
    Assertions.assertThat(response2.code()).isEqualTo(200);
    response2.body().close();

    List<String> recordedEvents = listener.recordedEventTypes();
    Assertions.assertThat(recordedEvents.contains("DnsStart")).isFalse();
    Assertions.assertThat(recordedEvents.contains("DnsEnd")).isFalse();
  }

  @Test public void failedDnsLookup() {
    client = client.newBuilder()
        .dns(new FakeDns())
        .build();
    Call call = client.newCall(new Request.Builder()
        .url("http://fakeurl/")
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    listener.removeUpToEvent(DnsStart.class);

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    Assertions.assertThat(callFailed.call).isSameAs(call);
    boolean condition = callFailed.ioe instanceof UnknownHostException;
    Assertions.assertThat(condition).isTrue();
  }

  @Test public void emptyDnsLookup() {
    Dns emptyDns = hostname -> Collections.emptyList();

    client = client.newBuilder()
        .dns(emptyDns)
        .build();
    Call call = client.newCall(new Request.Builder()
        .url("http://fakeurl/")
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    listener.removeUpToEvent(DnsStart.class);

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    Assertions.assertThat(callFailed.call).isSameAs(call);
    boolean condition = callFailed.ioe instanceof UnknownHostException;
    Assertions.assertThat(condition).isTrue();
  }

  @Test public void successfulConnect() throws IOException {
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    InetAddress address = client.dns().lookup(server.getHostName()).get(0);
    InetSocketAddress expectedAddress = new InetSocketAddress(address, server.getPort());

    ConnectStart connectStart = listener.removeUpToEvent(ConnectStart.class);
    Assertions.assertThat(connectStart.call).isSameAs(call);
    Assertions.assertThat(connectStart.inetSocketAddress).isEqualTo(expectedAddress);
    Assertions.assertThat(connectStart.proxy).isEqualTo(Proxy.NO_PROXY);

    ConnectEnd connectEnd = listener.removeUpToEvent(ConnectEnd.class);
    Assertions.assertThat(connectEnd.call).isSameAs(call);
    Assertions.assertThat(connectEnd.inetSocketAddress).isEqualTo(expectedAddress);
    Assertions.assertThat(connectEnd.protocol).isEqualTo(Protocol.HTTP_1_1);
  }

  @Test public void failedConnect() throws UnknownHostException {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    InetAddress address = client.dns().lookup(server.getHostName()).get(0);
    InetSocketAddress expectedAddress = new InetSocketAddress(address, server.getPort());

    ConnectStart connectStart = listener.removeUpToEvent(ConnectStart.class);
    Assertions.assertThat(connectStart.call).isSameAs(call);
    Assertions.assertThat(connectStart.inetSocketAddress).isEqualTo(expectedAddress);
    Assertions.assertThat(connectStart.proxy).isEqualTo(Proxy.NO_PROXY);

    ConnectFailed connectFailed = listener.removeUpToEvent(ConnectFailed.class);
    Assertions.assertThat(connectFailed.call).isSameAs(call);
    Assertions.assertThat(connectFailed.inetSocketAddress).isEqualTo(expectedAddress);
    Assertions.assertThat(connectFailed.protocol).isNull();
    Assertions.assertThat(connectFailed.ioe).isNotNull();
  }

  @Test public void multipleConnectsForSingleCall() throws IOException {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    listener.removeUpToEvent(ConnectStart.class);
    listener.removeUpToEvent(ConnectFailed.class);
    listener.removeUpToEvent(ConnectStart.class);
    listener.removeUpToEvent(ConnectEnd.class);
  }

  @Test public void successfulHttpProxyConnect() throws IOException {
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .proxy(server.toProxyAddress())
        .build();

    Call call = client.newCall(new Request.Builder()
        .url("http://www.fakeurl")
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    InetAddress address = client.dns().lookup(server.getHostName()).get(0);
    InetSocketAddress expectedAddress = new InetSocketAddress(address, server.getPort());

    ConnectStart connectStart = listener.removeUpToEvent(ConnectStart.class);
    Assertions.assertThat(connectStart.call).isSameAs(call);
    Assertions.assertThat(connectStart.inetSocketAddress).isEqualTo(expectedAddress);
    Assertions.assertThat(connectStart.proxy).isEqualTo(server.toProxyAddress());

    ConnectEnd connectEnd = listener.removeUpToEvent(ConnectEnd.class);
    Assertions.assertThat(connectEnd.call).isSameAs(call);
    Assertions.assertThat(connectEnd.inetSocketAddress).isEqualTo(expectedAddress);
    Assertions.assertThat(connectEnd.protocol).isEqualTo(Protocol.HTTP_1_1);
  }

  @Test public void successfulSocksProxyConnect() throws Exception {
    server.enqueue(new MockResponse());

    socksProxy = new SocksProxy();
    socksProxy.play();
    Proxy proxy = socksProxy.proxy();

    client = client.newBuilder()
        .proxy(proxy)
        .build();

    Call call = client.newCall(new Request.Builder()
        .url("http://" + SocksProxy.HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS + ":" + server.getPort())
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    InetSocketAddress expectedAddress = InetSocketAddress.createUnresolved(
        SocksProxy.HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS, server.getPort());

    ConnectStart connectStart = listener.removeUpToEvent(ConnectStart.class);
    Assertions.assertThat(connectStart.call).isSameAs(call);
    Assertions.assertThat(connectStart.inetSocketAddress).isEqualTo(expectedAddress);
    Assertions.assertThat(connectStart.proxy).isEqualTo(proxy);

    ConnectEnd connectEnd = listener.removeUpToEvent(ConnectEnd.class);
    Assertions.assertThat(connectEnd.call).isSameAs(call);
    Assertions.assertThat(connectEnd.inetSocketAddress).isEqualTo(expectedAddress);
    Assertions.assertThat(connectEnd.protocol).isEqualTo(Protocol.HTTP_1_1);
  }

  @Test public void authenticatingTunnelProxyConnect() throws IOException {
    enableTlsWithTunnel(true);
    server.enqueue(new MockResponse()
        .setResponseCode(407)
        .addHeader("Proxy-Authenticate: Basic realm=\"localhost\"")
        .addHeader("Connection: close"));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END));
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .proxy(server.toProxyAddress())
        .proxyAuthenticator(new RecordingOkAuthenticator("password", "Basic"))
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    listener.removeUpToEvent(ConnectStart.class);

    ConnectEnd connectEnd = listener.removeUpToEvent(ConnectEnd.class);
    Assertions.assertThat(connectEnd.protocol).isNull();

    listener.removeUpToEvent(ConnectStart.class);
    listener.removeUpToEvent(ConnectEnd.class);
  }

  @Test public void successfulSecureConnect() throws IOException {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    SecureConnectStart secureStart = listener.removeUpToEvent(SecureConnectStart.class);
    Assertions.assertThat(secureStart.call).isSameAs(call);

    SecureConnectEnd secureEnd = listener.removeUpToEvent(SecureConnectEnd.class);
    Assertions.assertThat(secureEnd.call).isSameAs(call);
    Assertions.assertThat(secureEnd.handshake).isNotNull();
  }

  @Test public void failedSecureConnect() {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    SecureConnectStart secureStart = listener.removeUpToEvent(SecureConnectStart.class);
    Assertions.assertThat(secureStart.call).isSameAs(call);

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    Assertions.assertThat(callFailed.call).isSameAs(call);
    Assertions.assertThat(callFailed.ioe).isNotNull();
  }

  @Test public void secureConnectWithTunnel() throws IOException {
    enableTlsWithTunnel(true);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END));
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .proxy(server.toProxyAddress())
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    SecureConnectStart secureStart = listener.removeUpToEvent(SecureConnectStart.class);
    Assertions.assertThat(secureStart.call).isSameAs(call);

    SecureConnectEnd secureEnd = listener.removeUpToEvent(SecureConnectEnd.class);
    Assertions.assertThat(secureEnd.call).isSameAs(call);
    Assertions.assertThat(secureEnd.handshake).isNotNull();
  }

  @Test public void multipleSecureConnectsForSingleCall() throws IOException {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    listener.removeUpToEvent(SecureConnectStart.class);
    listener.removeUpToEvent(ConnectFailed.class);

    listener.removeUpToEvent(SecureConnectStart.class);
    listener.removeUpToEvent(SecureConnectEnd.class);
  }

  @Test public void noSecureConnectsOnPooledConnection() throws IOException {
    enableTlsWithTunnel(false);
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .build();

    // Seed the pool.
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response1 = call1.execute();
    Assertions.assertThat(response1.code()).isEqualTo(200);
    response1.body().close();

    listener.clearAllEvents();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response2 = call2.execute();
    Assertions.assertThat(response2.code()).isEqualTo(200);
    response2.body().close();

    List<String> recordedEvents = listener.recordedEventTypes();
    Assertions.assertThat(recordedEvents.contains("SecureConnectStart")).isFalse();
    Assertions.assertThat(recordedEvents.contains("SecureConnectEnd")).isFalse();
  }

  @Test public void successfulConnectionFound() throws IOException {
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.code()).isEqualTo(200);
    response.body().close();

    ConnectionAcquired connectionAcquired = listener.removeUpToEvent(ConnectionAcquired.class);
    Assertions.assertThat(connectionAcquired.call).isSameAs(call);
    Assertions.assertThat(connectionAcquired.connection).isNotNull();
  }

  @Test public void pooledConnectionFound() throws IOException {
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    // Seed the pool.
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response1 = call1.execute();
    Assertions.assertThat(response1.code()).isEqualTo(200);
    response1.body().close();

    ConnectionAcquired connectionAcquired1 = listener.removeUpToEvent(ConnectionAcquired.class);
    listener.clearAllEvents();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response2 = call2.execute();
    Assertions.assertThat(response2.code()).isEqualTo(200);
    response2.body().close();

    ConnectionAcquired connectionAcquired2 = listener.removeUpToEvent(ConnectionAcquired.class);
    Assertions.assertThat(connectionAcquired2.connection).isSameAs(
        connectionAcquired1.connection);
  }

  @Test public void responseBodyFailHttp1OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(okhttp3.Protocol.HTTP_1_1));
    responseBodyFail();
  }

  @Test public void responseBodyFailHttp() throws IOException {
    responseBodyFail();
  }

  private void responseBodyFail() throws IOException {
    // Use a 2 MiB body so the disconnect won't happen until the client has read some data.
    int responseBodySize = 2 * 1024 * 1024; // 2 MiB
    server.enqueue(new MockResponse()
        .setBody(new Buffer().write(new byte[responseBodySize]))
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.protocol()).isEqualTo(Protocol.HTTP_1_1);
    try {
      response.body().string();
      fail();
    } catch (IOException expected) {
    }

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    Assertions.assertThat(callFailed.ioe).isNotNull();
  }

  @Test public void emptyResponseBody() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("")
        .setBodyDelay(1, TimeUnit.SECONDS)
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response = call.execute();
    response.body().close();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  @Test public void emptyResponseBodyConnectionClose() throws IOException {
    server.enqueue(new MockResponse()
        .addHeader("Connection", "close")
        .setBody(""));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response = call.execute();
    response.body().close();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  @Test public void responseBodyClosedClosedWithoutReadingAllData() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("abc")
        .setBodyDelay(1, TimeUnit.SECONDS)
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .build());
    Response response = call.execute();
    response.body().close();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd",
        "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  @Test public void requestBodyFailHttp1OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(List.of(okhttp3.Protocol.HTTP_1_1));
    requestBodyFail();
  }

  @Test public void requestBodyFailHttp() throws IOException {
    requestBodyFail();
  }

  private void requestBodyFail() {
    // Stream a 256 MiB body so the disconnect will happen before the server has read everything.
    RequestBody requestBody = new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.get("text/plain");
      }

      @Override public long contentLength() {
        return 1024 * 1024 * 256;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        for (int i = 0; i < 1024; i++) {
          sink.write(new byte[1024 * 256]);
          sink.flush();
        }
      }
    };

    server.setBodyLimit(0L);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .post(requestBody)
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    CallFailed callFailed = listener.removeUpToEvent(CallFailed.class);
    Assertions.assertThat(callFailed.ioe).isNotNull();
  }

  @Test public void requestBodyMultipleFailuresReportedOnlyOnce() {
    RequestBody requestBody = new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.get("text/plain");
      }

      @Override public long contentLength() {
        return 1024 * 1024 * 256;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        int failureCount = 0;
        for (int i = 0; i < 1024; i++) {
          try {
            sink.write(new byte[1024 * 256]);
            sink.flush();
          } catch (IOException e) {
            failureCount++;
            if (failureCount == 3) throw e;
          }
        }
      }
    };

    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .post(requestBody)
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd", "ConnectStart",
        "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart", "RequestHeadersEnd",
        "RequestBodyStart", "RequestFailed", "ConnectionReleased", "CallFailed");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }

  @Test public void requestBodySuccessHttp1OverHttps() throws IOException {
    enableTlsWithTunnel(false);
    server.setProtocols(Arrays.asList(okhttp3.Protocol.HTTP_1_1));
    requestBodySuccess(RequestBody.create(MediaType.get("text/plain"), "Hello"), equalTo(5L),
        equalTo(19L));
  }

  @Test public void requestBodySuccessHttp() throws IOException {
    requestBodySuccess(RequestBody.create(MediaType.get("text/plain"), "Hello"), equalTo(5L),
        equalTo(19L));
  }

  @Test public void requestBodySuccessStreaming() throws IOException {
    RequestBody requestBody = new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.get("text/plain");
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.write(new byte[8192]);
        sink.flush();
      }
    };

    requestBodySuccess(requestBody, equalTo(8192L), equalTo(19L));
  }

  @Test public void requestBodySuccessEmpty() throws IOException {
    requestBodySuccess(RequestBody.create(MediaType.get("text/plain"), ""), equalTo(0L),
        equalTo(19L));
  }

  private void requestBodySuccess(RequestBody body, Matcher<Long> requestBodyBytes,
      Matcher<Long> responseHeaderLength) throws IOException {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("World!"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").toString())
        .post(body)
        .build());
    Response response = call.execute();
    Assertions.assertThat(response.body().string()).isEqualTo("World!");

    assertBytesReadWritten(listener, any(Long.class), requestBodyBytes, responseHeaderLength,
        equalTo(6L));
  }

  private void enableTlsWithTunnel(boolean tunnelProxy) {
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();
    server.useHttps(handshakeCertificates.sslSocketFactory(), tunnelProxy);
  }

  /** Response headers start, then the entire request body, then response headers end. */
  @Test public void expectContinueStartsResponseHeadersEarly() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.EXPECT_CONTINUE));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .header("Expect", "100-continue")
        .post(RequestBody.create(MediaType.get("text/plain"), "abc"))
        .build();

    Call call = client.newCall(request);
    call.execute();

    List<String> expectedEvents = Arrays.asList("CallStart", "DnsStart", "DnsEnd", "ConnectStart",
        "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart", "RequestHeadersEnd",
        "ResponseHeadersStart", "RequestBodyStart", "RequestBodyEnd", "ResponseHeadersEnd",
        "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased", "CallEnd");
    Assertions.assertThat(listener.recordedEventTypes()).isEqualTo(expectedEvents);
  }
}
