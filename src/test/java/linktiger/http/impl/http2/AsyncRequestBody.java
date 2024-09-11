/*
 * Copyright (C) 2019 Square, Inc.
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
package linktiger.http.impl.http2;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import linktiger.http.MediaType;
import linktiger.http.RequestBody;

import okio.BufferedSink;

import static junit.framework.TestCase.assertTrue;

/** A duplex request body that keeps the provided sinks so they can be written to later. */
public final class AsyncRequestBody extends RequestBody {
  private final BlockingQueue<BufferedSink> requestBodySinks = new LinkedBlockingQueue<>();

  @Override public MediaType contentType() {
    return null;
  }

  @Override public void writeTo(BufferedSink sink) {
    requestBodySinks.add(sink);
  }

  @Override public boolean isDuplex() {
    return true;
  }

  public BufferedSink takeSink() throws InterruptedException {
    BufferedSink result = requestBodySinks.poll(5, TimeUnit.SECONDS);
    if (result == null) throw new AssertionError("no sink to take");
    return result;
  }

  public void assertNoMoreSinks() {
    assertTrue(requestBodySinks.isEmpty());
  }
}
