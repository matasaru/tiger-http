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
package linktiger.http;

import java.io.IOException;
import java.net.ProtocolException;

import kio.Buffer;
import kio.ForwardingSink;
import kio.ForwardingSource;
import kio.RealBufferedSource;
import kio.Sink;
import kio.Source;

/**
 * Transmits a single HTTP request and a response pair. This layers connection management and events
 * on {@link Http1Codec}, which handles the actual I/O.
 */
public final class Exchange {
  final Transmitter transmitter;
  final ExchangeFinder finder;
  final Http1Codec codec;

  public Exchange(Transmitter transmitter, ExchangeFinder finder, Http1Codec codec) {
    this.transmitter = transmitter;
    this.finder = finder;
    this.codec = codec;
  }

  public Connection connection() {
    return codec.connection();
  }

  public void writeRequestHeaders(Request request) throws IOException {
    try {
      codec.writeRequestHeaders(request);
    } catch (IOException e) {
      trackFailure(e);
      throw e;
    }
  }

  public Sink createRequestBody(Request request) throws IOException {
    int contentLength = request.body().contentLength();
    Sink rawRequestBody = codec.createRequestBody();
    return new RequestBodySink(rawRequestBody, contentLength);
  }

  public void finishRequest() throws IOException {
    try {
      codec.finishRequest();
    } catch (IOException e) {
      trackFailure(e);
      throw e;
    }
  }

  public Response.Builder readResponseHeaders() throws IOException {
    try {
      Response.Builder result = codec.readResponseHeaders();
      if (result != null) {
        result.initExchange(this);
      }
      return result;
    } catch (IOException e) {
      trackFailure(e);
      throw e;
    }
  }

  public ResponseBody openResponseBody(Response response) throws IOException {
    String contentType = response.header("Content-Type");
    int contentLength = codec.reportedContentLength(response);
    Source rawSource = codec.openResponseBodySource(response);
    ResponseBodySource source = new ResponseBodySource(rawSource, contentLength);
    return new ResponseBody(contentType, contentLength, new RealBufferedSource(source));
  }

  public void noNewExchangesOnConnection() {
    codec.connection().noNewExchanges();
  }

  public void cancel() {
    codec.cancel();
  }

  void trackFailure(IOException e) {
    finder.trackFailure();
    codec.connection().trackFailure(e);
  }

  IOException bodyComplete(boolean responseDone, boolean requestDone, IOException e) {
    if (e != null) {
      trackFailure(e);
    }
    return transmitter.exchangeMessageDone(this, requestDone, responseDone, e);
  }

  public void noRequestBody() {
    transmitter.exchangeMessageDone(this, true, false, null);
  }

  /** A request body that fires events when it completes. */
  private final class RequestBodySink extends ForwardingSink {
    private boolean completed;
    /** The exact number of bytes to be written, or -1L if that is unknown. */
    private int contentLength;
    private int bytesReceived;
    private boolean closed;

    RequestBodySink(Sink delegate, int contentLength) {
      super(delegate);
      this.contentLength = contentLength;
    }

    @Override public void write(Buffer source, int byteCount) throws IOException {
      if (closed) throw new IllegalStateException("closed");
      if (contentLength != -1L && bytesReceived + byteCount > contentLength) {
        throw new ProtocolException("expected " + contentLength
            + " bytes but received " + (bytesReceived + byteCount));
      }
      try {
        super.write(source, byteCount);
        this.bytesReceived += byteCount;
      } catch (IOException e) {
        throw complete(e);
      }
    }

    @Override public void flush() throws IOException {
      try {
        super.flush();
      } catch (IOException e) {
        throw complete(e);
      }
    }

    @Override public void close() throws IOException {
      if (closed) return;
      closed = true;
      if (contentLength != -1L && bytesReceived != contentLength) {
        throw new ProtocolException("unexpected end of stream");
      }
      try {
        super.close();
        complete(null);
      } catch (IOException e) {
        throw complete(e);
      }
    }

    private IOException complete(IOException e) {
      if (completed) return e;
      completed = true;
      return bodyComplete(false, true, e);
    }
  }

  /** A response body that fires events when it completes. */
  final class ResponseBodySource extends ForwardingSource {
    private final int contentLength;
    private int bytesReceived;
    private boolean completed;
    private boolean closed;

    ResponseBodySource(Source delegate, int contentLength) {
      super(delegate);
      this.contentLength = contentLength;

      if (contentLength == 0L) {
        complete(null);
      }
    }

    @Override public int read(Buffer sink, int byteCount) throws IOException {
      if (closed) throw new IllegalStateException("closed");
      try {
        int read = delegate().read(sink, byteCount);
        if (read == -1) {
          complete(null);
          return -1;
        }

        int newBytesReceived = bytesReceived + read;
        if (contentLength != -1L && newBytesReceived > contentLength) {
          throw new ProtocolException("expected " + contentLength
              + " bytes but received " + newBytesReceived);
        }

        bytesReceived = newBytesReceived;
        if (newBytesReceived == contentLength) {
          complete(null);
        }

        return read;
      } catch (IOException e) {
        throw complete(e);
      }
    }

    @Override public void close() throws IOException {
      if (closed) return;
      closed = true;
      try {
        super.close();
        complete(null);
      } catch (IOException e) {
        throw complete(e);
      }
    }

    IOException complete(IOException e) {
      if (completed) return e;
      completed = true;
      return bodyComplete(true, false, e);
    }
  }
}
