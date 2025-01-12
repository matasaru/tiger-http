/*
 * Copyright (C) 2014 Square, Inc.
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

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.ProxySelector;
import java.net.ResponseCache;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocketFactory;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static linktiger.http.TestUtil.defaultClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class HttpClientTest {
  private static final ProxySelector DEFAULT_PROXY_SELECTOR = ProxySelector.getDefault();
  private static final CookieHandler DEFAULT_COOKIE_HANDLER = CookieManager.getDefault();
  private static final ResponseCache DEFAULT_RESPONSE_CACHE = ResponseCache.getDefault();
  private final MockWebServer server = new MockWebServer();

  @Before public void setUp() throws Exception {
    server.start();
  }

  @After public void tearDown() throws Exception {
    server.shutdown();
    ProxySelector.setDefault(DEFAULT_PROXY_SELECTOR);
    CookieManager.setDefault(DEFAULT_COOKIE_HANDLER);
    ResponseCache.setDefault(DEFAULT_RESPONSE_CACHE);
  }

  @Test public void durationDefaults() {
    HttpClient client = defaultClient();
    assertThat(client.callTimeoutMillis()).isEqualTo(0);
    assertThat(client.connectTimeoutMillis()).isEqualTo(10_000);
    assertThat(client.readTimeoutMillis()).isEqualTo(10_000);
    assertThat(client.writeTimeoutMillis()).isEqualTo(10_000);
  }

  @Test public void timeoutValidRange() {
    HttpClient.Builder builder = new HttpClient.Builder();
    try {
      builder.callTimeout(1, TimeUnit.NANOSECONDS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.connectTimeout(1, TimeUnit.NANOSECONDS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.writeTimeout(1, TimeUnit.NANOSECONDS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.readTimeout(1, TimeUnit.NANOSECONDS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.callTimeout(365, TimeUnit.DAYS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.connectTimeout(365, TimeUnit.DAYS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.writeTimeout(365, TimeUnit.DAYS);
    } catch (IllegalArgumentException ignored) {
    }
    try {
      builder.readTimeout(365, TimeUnit.DAYS);
    } catch (IllegalArgumentException ignored) {
    }
  }

  /**
   * When copying the client, stateful things like the connection pool are shared across all
   * clients.
   */
  @Test public void cloneSharesStatefulInstances() throws Exception {
    HttpClient client = defaultClient();

    // Values should be non-null.
    HttpClient a = client.newBuilder().build();
    assertThat(a.connectionPool()).isNotNull();
    assertThat(a.sslSocketFactory()).isNotNull();

    // Multiple clients share the instances.
    HttpClient b = client.newBuilder().build();
    assertThat(b.connectionPool()).isSameAs(a.connectionPool());
    assertThat(b.sslSocketFactory()).isSameAs(a.sslSocketFactory());
  }

  @Test public void setProtocolsRejectsHttp10() throws Exception {
    HttpClient.Builder builder = new HttpClient.Builder();
    try {
      builder.protocols(Arrays.asList(Protocol.HTTP_1_0, Protocol.HTTP_1_1));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void nullDefaultProxySelector() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    ProxySelector.setDefault(null);

    HttpClient client = defaultClient().newBuilder()
        .build();

    Request request = new Request.Builder().url(server.url("/").toString()).build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("abc");
  }

  @Test public void sslSocketFactorySetAsSocketFactory() throws Exception {
    HttpClient.Builder builder = new HttpClient.Builder();
    try {
      builder.socketFactory(SSLSocketFactory.getDefault());
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
