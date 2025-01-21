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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

import kio.Buffer;
import kio.BufferedSource;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A one-shot stream from the origin server to the client application with the raw bytes of the
 * response body. Each response body is supported by an active connection to the webserver. This
 * imposes both obligations and limits on the client application.
 *
 * <h3>The response body must be closed.</h3>
 *
 * Each response body is backed by a socket. Failing to close the response body will leak resources
 * and may ultimately cause the application to slow down or crash.
 *
 * <p>Both this class and {@link Response} implement {@link Closeable}. Closing a response simply
 * closes its response body. If you invoke {@link Call#execute()} you must close this body by calling
 * any of the following methods:
 *
 * <ul>
 *   <li>Response.close()</li>
 *   <li>Response.body().close()</li>
 *   <li>Response.body().source().close()</li>
 *   <li>Response.body().charStream().close()</li>
 *   <li>Response.body().byteStream().close()</li>
 *   <li>Response.body().bytes()</li>
 *   <li>Response.body().string()</li>
 * </ul>
 *
 * <p>There is no benefit to invoking multiple {@code close()} methods for the same response body.
 *
 * <p>The easiest way to make sure a response body is closed is with a {@code try} block.
 * With this structure the compiler inserts an implicit {@code finally} clause that calls {@code close()} for you.
 *
 * <pre>   {@code
 *
 *   Call call = client.newCall(request);
 *   try (Response response = call.execute()) {
 *     ... // Use the response.
 *   }
 * }</pre>
 *
 * This example will not work if you're consuming the response body on another thread. In such
 * cases the consuming thread must call {@link #close} when it has finished reading the response
 * body.
 *
 * <h3>The response body can be consumed only once.</h3>
 *
 * <p>This class may be used to stream very large responses. For example, it is possible to use this
 * class to read a response that is larger than the entire memory allocated to the current process.
 * It can even stream a response larger than the total storage on the current device, which is a
 * common requirement for video streaming applications.
 *
 * <p>Because this class does not buffer the full response in memory, the application may not
 * re-read the bytes of the response. Use this one shot to read the entire response into memory with
 * {@link #bytes()}. Or stream the response with either {@link #source()},
 * {@link #byteStream()}.
 */
public class ResponseBody implements Closeable {

  public static final ResponseBody EMPTY = new ResponseBody(null, 0, new Buffer().write(Util.EMPTY_BYTE_ARRAY));

  /**
   * Use a string to avoid parsing the content type until needed. This also defers problems caused
   * by malformed content types.
   */
  private final String contentType;
  private final long contentLength;
  private final BufferedSource source;

  public ResponseBody(String contentType, long contentLength, BufferedSource source) {
    this.contentType = contentType;
    this.contentLength = contentLength;
    this.source = source;
  }

  public String contentType() {
    return contentType;
  }

  /**
   * Returns the number of bytes in that will returned by {@link #bytes}, or {@link #byteStream}, or
   * -1 if unknown.
   */
  public long contentLength() {
    return contentLength;
  }

  public final InputStream byteStream() {
    return source().inputStream();
  }

  public BufferedSource source() {
    return source;
  }

  /**
   * Returns the response as a byte array.
   *
   * <p>This method loads entire response body into memory. If the response body is very large this
   * may trigger an {@link OutOfMemoryError}. Prefer to stream the response body if this is a
   * possibility for your response.
   */
  public final byte[] bytes() throws IOException {
    long contentLength = contentLength();
    if (contentLength > Integer.MAX_VALUE) {
      throw new IOException("Cannot buffer entire body for content length: " + contentLength);
    }

    byte[] bytes;
    try (BufferedSource source = source()) {
      bytes = source.readByteArray();
    }
    if (contentLength != -1 && contentLength != bytes.length) {
      throw new IOException("Content-Length ("
          + contentLength
          + ") and stream length ("
          + bytes.length
          + ") disagree");
    }
    return bytes;
  }

  public Charset charset() {
    var contentType = contentType();
    if (contentType == null) {
      return UTF_8;
    }
    var mediaType = MediaType.parse(contentType);
    if (mediaType == null) {
      return UTF_8;
    }
    return mediaType.charset(UTF_8);
  }

  @Override public void close() {
    if (source() != null) {
      try {
        source().close();
      } catch (IOException _) {
      }
    }
  }
}
