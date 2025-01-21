/*
 * Copyright (C) 2016 Square, Inc.
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import kio.Buffer;
import kio.BufferedSource;
import kio.ForwardingSource;
import kio.RealBufferedSource;
import org.junit.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class ResponseBodyTest {
  @Test public void stringEmpty() throws IOException {
    ResponseBody body = body("");
    assertThat(body.bytes()).isEqualTo("".getBytes(UTF_8));
  }

  @Test public void stringClosesUnderlyingSource() throws IOException {
    final AtomicBoolean closed = new AtomicBoolean();
    ResponseBody body = new ResponseBody(null, 5, null) {
      @Override public BufferedSource source() {
        Buffer source = new Buffer().writeUtf8("hello");
        return new RealBufferedSource(new ForwardingSource(source) {
          @Override public void close() throws IOException {
            closed.set(true);
            super.close();
          }
        });
      }
    };
    assertThat(body.bytes()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    assertThat(closed.get()).isTrue();
  }

  @Test public void sourceEmpty() throws IOException {
    ResponseBody body = body("");
    BufferedSource source = body.source();
    assertThat(source.exhausted()).isTrue();
    assertThat(source.readUtf8()).isEqualTo("");
  }

  @Test public void sourceSeesBom() throws IOException {
    ResponseBody body = body("efbbbf68656c6c6f");
    BufferedSource source = body.source();
    assertThat((source.readByte() & 0xff)).isEqualTo(0xef);
    assertThat((source.readByte() & 0xff)).isEqualTo(0xbb);
    assertThat((source.readByte() & 0xff)).isEqualTo(0xbf);
    assertThat(source.readUtf8()).isEqualTo("hello");
  }

  @Test public void sourceClosesUnderlyingSource() throws IOException {
    final AtomicBoolean closed = new AtomicBoolean();
    ResponseBody body = new ResponseBody(null, 5, null) {
      @Override public BufferedSource source() {
        Buffer source = new Buffer().writeUtf8("hello");
        return new RealBufferedSource(new ForwardingSource(source) {
          @Override public void close() throws IOException {
            closed.set(true);
            super.close();
          }
        });
      }
    };
    body.source().close();
    assertThat(closed.get()).isTrue();
  }

  @Test public void bytesEmpty() throws IOException {
    ResponseBody body = body("");
    assertThat(body.bytes().length).isEqualTo(0);
  }

  @Test public void bytesSeesBom() throws IOException {
    ResponseBody body = body("efbbbf68656c6c6f");
    byte[] bytes = body.bytes();
    assertThat((bytes[0] & 0xff)).isEqualTo(0xef);
    assertThat((bytes[1] & 0xff)).isEqualTo(0xbb);
    assertThat((bytes[2] & 0xff)).isEqualTo(0xbf);
    assertThat(new String(bytes, 3, 5, UTF_8)).isEqualTo("hello");
  }

  @Test public void bytesClosesUnderlyingSource() throws IOException {
    final AtomicBoolean closed = new AtomicBoolean();
    ResponseBody body = new ResponseBody(null, 5, null) {
      @Override public BufferedSource source() {
        Buffer source = new Buffer().writeUtf8("hello");
        return new RealBufferedSource(new ForwardingSource(source) {
          @Override public void close() throws IOException {
            closed.set(true);
            super.close();
          }
        });
      }
    };
    assertThat(body.bytes().length).isEqualTo(5);
    assertThat(closed.get()).isTrue();
  }

  @Test public void bytesThrowsWhenLengthsDisagree() {
    ResponseBody body = new ResponseBody(null, 10, new Buffer().writeUtf8("hello"));
    try {
      body.bytes();
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).isEqualTo(
          "Content-Length (10) and stream length (5) disagree");
    }
  }

  @Test public void bytesThrowsMoreThanIntMaxValue() {
    ResponseBody body = new ResponseBody(null, Integer.MAX_VALUE + 1L, null) {
      @Override public BufferedSource source() {
        throw new AssertionError();
      }
    };
    try {
      body.bytes();
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).isEqualTo(
          "Cannot buffer entire body for content length: 2147483648");
    }
  }

  @Test public void byteStreamEmpty() throws IOException {
    ResponseBody body = body("");
    InputStream bytes = body.byteStream();
    assertThat(bytes.read()).isEqualTo(-1);
  }

  @Test public void byteStreamSeesBom() throws IOException {
    ResponseBody body = body("efbbbf68656c6c6f");
    InputStream bytes = body.byteStream();
    assertThat(bytes.read()).isEqualTo(0xef);
    assertThat(bytes.read()).isEqualTo(0xbb);
    assertThat(bytes.read()).isEqualTo(0xbf);
    assertThat(exhaust(new InputStreamReader(bytes, UTF_8))).isEqualTo("hello");
  }

  @Test public void byteStreamClosesUnderlyingSource() throws IOException {
    final AtomicBoolean closed = new AtomicBoolean();
    ResponseBody body = new ResponseBody(null, 5, null) {
      @Override public BufferedSource source() {
        Buffer source = new Buffer().writeUtf8("hello");
        return new RealBufferedSource(new ForwardingSource(source) {
          @Override public void close() throws IOException {
            closed.set(true);
            super.close();
          }
        });
      }
    };
    body.byteStream().close();
    assertThat(closed.get()).isTrue();
  }

  @Test public void throwingUnderlyingSourceClosesQuietly() throws IOException {
    ResponseBody body = new ResponseBody(null, 5, null) {
      @Override public BufferedSource source() {
        Buffer source = new Buffer().writeUtf8("hello");
        return new RealBufferedSource(new ForwardingSource(source) {
          @Override public void close() throws IOException {
            throw new IOException("Broken!");
          }
        });
      }
    };
    assertThat(body.source().readUtf8()).isEqualTo("hello");
    body.close();
  }

  static ResponseBody body(String hex) {
    return body(hex, null);
  }

  static ResponseBody body(String hex, String charset) {
    String mediaType = charset == null ? null : MediaType.get("any/thing; charset=" + charset).toString();
    var bytes =HexFormat.of().parseHex(hex);
    return new ResponseBody(mediaType, bytes.length, new Buffer().write(bytes));
  }

  static String exhaust(Reader reader) throws IOException {
    StringBuilder builder = new StringBuilder();
    char[] buf = new char[10];
    int read;
    while ((read = reader.read(buf)) != -1) {
      builder.append(buf, 0, read);
    }
    return builder.toString();
  }
}
