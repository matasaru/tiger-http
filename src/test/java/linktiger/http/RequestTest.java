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
import java.net.URI;
import java.util.HexFormat;

import kio.Buffer;
import org.junit.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class RequestTest {

  @Test public void byteArray() throws Exception {
    MediaType contentType = MediaType.get("text/plain");
    RequestBody body = new RequestBody("abc".getBytes(UTF_8), contentType);
    assertThat(body.contentType()).isEqualTo(contentType);
    assertThat(body.content().length).isEqualTo(3);
    assertThat(bodyToHex(body)).isEqualTo("616263");
    assertThat(bodyToHex(body)).overridingErrorMessage("Retransmit body").isEqualTo("616263");
  }

  /** Common verbs used for apis such as GitHub, AWS, and Google Cloud. */
  @Test public void crudVerbs() throws IOException {
    MediaType contentType = MediaType.get("application/json");
    RequestBody body = new RequestBody("{}".getBytes(UTF_8), contentType);

    Request get = new Request.Builder().url("http://localhost/api").get().build();
    assertThat(get.method()).isEqualTo("GET");
    assertThat(get.body()).isNull();

    Request head = new Request.Builder().url("http://localhost/api").head().build();
    assertThat(head.method()).isEqualTo("HEAD");
    assertThat(head.body()).isNull();

    Request delete = new Request.Builder().url("http://localhost/api").delete().build();
    assertThat(delete.method()).isEqualTo("DELETE");
    assertThat(delete.body().content().length).isEqualTo(0L);

    Request post = new Request.Builder().url("http://localhost/api").post(body).build();
    assertThat(post.method()).isEqualTo("POST");
    assertThat(post.body()).isEqualTo(body);

    Request put = new Request.Builder().url("http://localhost/api").put(body).build();
    assertThat(put.method()).isEqualTo("PUT");
    assertThat(put.body()).isEqualTo(body);

    Request patch = new Request.Builder().url("http://localhost/api").patch(body).build();
    assertThat(patch.method()).isEqualTo("PATCH");
    assertThat(patch.body()).isEqualTo(body);
  }

  @Test public void uninitializedURI() throws Exception {
    Request request = new Request.Builder().url("http://localhost/api").build();
    assertThat(request.url().uri()).isEqualTo(new URI("http://localhost/api"));
    assertThat(request.url()).isEqualTo(Url.get("http://localhost/api"));
  }

  @Test public void newBuilderUrlResetsUrl() {
    Request requestWithoutCache = new Request.Builder().url("http://localhost/api").build();
    Request builtRequestWithoutCache =
        requestWithoutCache.newBuilder().url("http://localhost/api/foo").build();
    assertThat(builtRequestWithoutCache.url()).isEqualTo(
        Url.get("http://localhost/api/foo"));

    Request requestWithCache = new Request.Builder().url("http://localhost/api").build();
    // cache url object
    requestWithCache.url();
    Request builtRequestWithCache = requestWithCache.newBuilder().url(
        "http://localhost/api/foo").build();
    assertThat(builtRequestWithCache.url()).isEqualTo(
        Url.get("http://localhost/api/foo"));
  }

  @Test public void headerAcceptsPermittedCharacters() {
    Request.Builder builder = new Request.Builder();
    builder.header("AZab09~", "AZab09 ~");
    builder.addHeader("AZab09~", "AZab09 ~");
  }

  @Test public void emptyNameForbidden() {
    Request.Builder builder = new Request.Builder();
    try {
      builder.header("", "Value");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      builder.addHeader("", "Value");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void headerForbidsNullArguments() {
    Request.Builder builder = new Request.Builder();
    try {
      builder.header(null, "Value");
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      builder.addHeader(null, "Value");
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      builder.header("Name", null);
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      builder.addHeader("Name", null);
      fail();
    } catch (NullPointerException expected) {
    }
  }

  @Test public void headerAllowsTabOnlyInValues() {
    Request.Builder builder = new Request.Builder();
    builder.header("key", "sample\tvalue");
    try {
      builder.header("sample\tkey", "value");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void headerForbidsControlCharacters() {
    assertForbiddenHeader("\u0000");
    assertForbiddenHeader("\r");
    assertForbiddenHeader("\n");
    assertForbiddenHeader("\u001f");
    assertForbiddenHeader("\u007f");
    assertForbiddenHeader("\u0080");
    assertForbiddenHeader("\ud83c\udf69");
  }

  private void assertForbiddenHeader(String s) {
    Request.Builder builder = new Request.Builder();
    try {
      builder.header(s, "Value");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      builder.addHeader(s, "Value");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      builder.header("Name", s);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      builder.addHeader("Name", s);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  private String bodyToHex(RequestBody body) throws IOException {
    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    byte[] data = buffer.readByteArray();
    return HexFormat.of().formatHex(data);
  }
}
