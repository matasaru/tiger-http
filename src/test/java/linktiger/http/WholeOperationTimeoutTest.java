/*
 * Copyright (C) 2018 Square, Inc.
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
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.BufferedSink;
import org.junit.Rule;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class WholeOperationTimeoutTest {
  /** A large response body. Smaller bodies might successfully read after the socket is closed! */
  private static final String BIG_ENOUGH_BODY = TestUtil.repeat('a', 64 * 1024);

  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final HttpClientTestRule clientTestRule = new HttpClientTestRule();

  private HttpClient client = clientTestRule.client;

  @Test public void defaultConfigIsNoTimeout() throws Exception {
    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .build();
    Call call = client.newCall(request);
    assertThat(call.timeout().timeoutNanos()).isEqualTo(0);
  }

  @Test public void configureClientDefault() throws Exception {
    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .build();

    HttpClient timeoutClient = client.newBuilder()
        .callTimeout(456, TimeUnit.MILLISECONDS)
        .build();

    Call call = timeoutClient.newCall(request);
    assertThat(call.timeout().timeoutNanos()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(456));
  }

  @Test public void timeoutWritingRequest() throws Exception {
    server.enqueue(new MockResponse());

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .post(sleepingRequestBody(500))
        .build();

    Call call = client.newCall(request);
    call.timeout().timeout(250, TimeUnit.MILLISECONDS);
    try {
      call.execute();
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).isEqualTo("timeout");
      assertThat(call.isCanceled()).isTrue();
    }
  }

  @Test public void timeoutProcessing() throws Exception {
    server.enqueue(new MockResponse()
        .setHeadersDelay(500, TimeUnit.MILLISECONDS));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .build();

    Call call = client.newCall(request);
    call.timeout().timeout(250, TimeUnit.MILLISECONDS);
    try {
      call.execute();
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).isEqualTo("timeout");
      assertThat(call.isCanceled()).isTrue();
    }
  }

  @Test public void timeoutReadingResponse() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(BIG_ENOUGH_BODY));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .build();

    Call call = client.newCall(request);
    call.timeout().timeout(250, TimeUnit.MILLISECONDS);
    Response response = call.execute();
    Thread.sleep(500);
    try {
      response.body().source().readUtf8();
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).isEqualTo("timeout");
      assertThat(call.isCanceled()).isTrue();
    }
  }

  @Test public void noTimeout() throws Exception {
    server.enqueue(new MockResponse()
        .setHeadersDelay(250, TimeUnit.MILLISECONDS)
        .setBody(BIG_ENOUGH_BODY));

    Request request = new Request.Builder()
        .url(server.url("/").toString())
        .post(sleepingRequestBody(250))
        .build();

    Call call = client.newCall(request);
    call.timeout().timeout(1000, TimeUnit.MILLISECONDS);
    Response response = call.execute();
    Thread.sleep(250);
    response.body().source().readUtf8();
    response.close();
    assertThat(call.isCanceled()).isFalse();
  }

  private RequestBody sleepingRequestBody(final int sleepMillis) {
    return new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.parse("text/plain");
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        try {
          sink.writeUtf8("abc");
          sink.flush();
          Thread.sleep(sleepMillis);
          sink.writeUtf8("def");
        } catch (InterruptedException e) {
          throw new InterruptedIOException();
        }
      }
    };
  }
}
