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

import java.io.IOException;

import kio.BufferedSink;

public class RequestBody {

  public static final RequestBody EMPTY = new RequestBody(Util.EMPTY_BYTE_ARRAY, null);
  private final byte[] content;
  private final MediaType contentType;

  public RequestBody(byte[] content, MediaType contentType) {
    this.content = content;
    this.contentType = contentType;
  }

  public byte[] content() {
    return content;
  }

  /** Returns the Content-Type header for this body. */
  public MediaType contentType() {
    return contentType;
  }

  /**
   * Returns the number of bytes that will be written to {@code sink} in a call to {@link #writeTo}.
   */
  public int contentLength() {
    return content.length;
  }

  /** Writes the content of this request to {@code sink}. */
  public void writeTo(BufferedSink sink) throws IOException {
    sink.write(content);
  }
}
