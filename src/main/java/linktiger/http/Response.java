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

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;

/**
 * An HTTP response. Instances of this class are not immutable: the response body is a one-shot
 * value that may be consumed only once and then closed. All other properties are immutable.
 *
 * <p>This class implements {@link Closeable}. Closing it simply closes its response body. See
 * {@link ResponseBody} for an explanation and examples.
 */
public final class Response implements Closeable {
  final Request request;
  final Protocol protocol;
  final int code;
  final String message;
  final Handshake handshake;
  final Headers headers;
  final ResponseBody body;
  final long sentRequestAtMillis;
  final long receivedResponseAtMillis;
  final Exchange exchange;

  Response(Builder builder) {
    this.request = builder.request;
    this.protocol = builder.protocol;
    this.code = builder.code;
    this.message = builder.message;
    this.handshake = builder.handshake;
    this.headers = builder.headers.build();
    this.body = builder.body;
    this.sentRequestAtMillis = builder.sentRequestAtMillis;
    this.receivedResponseAtMillis = builder.receivedResponseAtMillis;
    this.exchange = builder.exchange;
  }

  /**
   * The wire-level request that initiated this HTTP response. This is not necessarily the same
   * request issued by the application:
   *
   * <ul>
   *     <li>It may be transformed by the HTTP client. For example, the client may copy headers like
   *         {@code Content-Length} from the request body.
   *     <li>It may be the request generated in response to an HTTP redirect or authentication
   *         challenge. In this case the request URL may be different than the initial request URL.
   * </ul>
   */
  public Request request() {
    return request;
  }

  /**
   * Returns the HTTP protocol, such as {@link Protocol#HTTP_1_1} or {@link Protocol#HTTP_1_0}.
   */
  public Protocol protocol() {
    return protocol;
  }

  /** Returns the HTTP status code. */
  public int code() {
    return code;
  }

  /**
   * Returns true if the code is in [200..300), which means the request was successfully received,
   * understood, and accepted.
   */
  public boolean isSuccessful() {
    return code >= 200 && code < 300;
  }

  /** Returns the HTTP status message. */
  public String message() {
    return message;
  }

  /**
   * Returns the TLS handshake of the connection that carried this response, or null if the response
   * was received without TLS.
   */
  public Handshake handshake() {
    return handshake;
  }

  public List<String> headers(String name) {
    return headers.values(name);
  }

  public String header(String name) {
    return header(name, null);
  }

  public String header(String name, String defaultValue) {
    String result = headers.get(name);
    return result != null ? result : defaultValue;
  }

  public Headers headers() {
    return headers;
  }

  /**
   * Returns the trailers after the HTTP response, which may be empty. It is an error to call this
   * before the entire HTTP response body has been consumed.
   */
  public Headers trailers() throws IOException {
    if (exchange == null) throw new IllegalStateException("trailers not available");
    return exchange.trailers();
  }

  /**
   * Returns a non-null value if this response was returned from {@link Call#execute()}.
   * Response bodies must be {@linkplain ResponseBody closed} and may be consumed only once.
   */
  public ResponseBody body() {
    return body;
  }

  /** Returns true if the response must have a (possibly 0-length) body. See RFC 7231. */
  public boolean hasBody() {
    // HEAD requests never yield a body regardless of the response headers.
    if (this.request().method().equals("HEAD")) {
      return false;
    }

    int responseCode = this.code();
    if ((responseCode < StatusLine.HTTP_CONTINUE || responseCode >= 200)
        && responseCode != HTTP_NO_CONTENT
        && responseCode != HTTP_NOT_MODIFIED) {
      return true;
    }

    // If the Content-Length or Transfer-Encoding headers disagree with the response code, the
    // response is malformed. For best compatibility, we honor the headers.
    if (HttpHeaders.contentLength(this) != -1 || "chunked".equalsIgnoreCase(this.header("Transfer-Encoding"))) {
      return true;
    }

    return false;
  }

  public Builder newBuilder() {
    return new Builder(this);
  }

  /** Returns true if this response redirects to another resource. */
  public boolean isRedirect() {
    switch (code) {
      case StatusLine.HTTP_PERM_REDIRECT:
      case StatusLine.HTTP_TEMP_REDIRECT:
      case HTTP_MULT_CHOICE:
      case HTTP_MOVED_PERM:
      case HTTP_MOVED_TEMP:
      case HTTP_SEE_OTHER:
        return true;
      default:
        return false;
    }
  }

  /**
   * Returns the RFC 7235 authorization challenges appropriate for this response's code. If the
   * response code is 401 unauthorized, this returns the "WWW-Authenticate" challenges. If the
   * response code is 407 proxy unauthorized, this returns the "Proxy-Authenticate" challenges.
   * Otherwise this returns an empty list of challenges.
   *
   * <p>If a challenge uses the {@code token68} variant instead of auth params, there is exactly one
   * auth param in the challenge at key {@code null}. Invalid headers and challenges are ignored.
   * No semantic validation is done, for example that {@code Basic} auth must have a {@code realm}
   * auth param, this is up to the caller that interprets these challenges.
   */
  public List<Challenge> challenges() {
    String responseField;
    if (code == HTTP_UNAUTHORIZED) {
      responseField = "WWW-Authenticate";
    } else if (code == HTTP_PROXY_AUTH) {
      responseField = "Proxy-Authenticate";
    } else {
      return Collections.emptyList();
    }
    return HttpHeaders.parseChallenges(headers(), responseField);
  }

  /**
   * Returns a {@linkplain System#currentTimeMillis() timestamp} taken immediately before OkHttp
   * transmitted the initiating request over the network.
   */
  public long sentRequestAtMillis() {
    return sentRequestAtMillis;
  }

  /**
   * Returns a {@linkplain System#currentTimeMillis() timestamp} taken immediately after OkHttp
   * received this response's headers from the network.
   */
  public long receivedResponseAtMillis() {
    return receivedResponseAtMillis;
  }

  public Exchange exchange() {
    return exchange;
  }

  /**
   * Closes the response body. Equivalent to {@code body().close()}.
   */
  @Override public void close() {
    if (body == null) {
      throw new IllegalStateException("response is not eligible for a body and must not be closed");
    }
    body.close();
  }

  @Override public String toString() {
    return "Response{protocol="
        + protocol
        + ", code="
        + code
        + ", message="
        + message
        + ", url="
        + request.url()
        + '}';
  }

  public static class Builder {
    Request request;
    Protocol protocol;
    int code = -1;
    String message;
    Handshake handshake;
    Headers.Builder headers;
    ResponseBody body;
    long sentRequestAtMillis;
    long receivedResponseAtMillis;
    Exchange exchange;

    public Builder() {
      headers = new Headers.Builder();
    }

    Builder(Response response) {
      this.request = response.request;
      this.protocol = response.protocol;
      this.code = response.code;
      this.message = response.message;
      this.handshake = response.handshake;
      this.headers = response.headers.newBuilder();
      this.body = response.body;
      this.sentRequestAtMillis = response.sentRequestAtMillis;
      this.receivedResponseAtMillis = response.receivedResponseAtMillis;
      this.exchange = response.exchange;
    }

    public Builder request(Request request) {
      this.request = request;
      return this;
    }

    public Builder protocol(Protocol protocol) {
      this.protocol = protocol;
      return this;
    }

    public int code() {
      return code;
    }

    public Builder code(int code) {
      this.code = code;
      return this;
    }

    public Builder message(String message) {
      this.message = message;
      return this;
    }

    public Builder handshake(Handshake handshake) {
      this.handshake = handshake;
      return this;
    }

    /**
     * Sets the header named {@code name} to {@code value}. If this request already has any headers
     * with that name, they are all replaced.
     */
    public Builder header(String name, String value) {
      headers.set(name, value);
      return this;
    }

    /**
     * Adds a header with {@code name} and {@code value}. Prefer this method for multiply-valued
     * headers like "Set-Cookie".
     */
    public Builder addHeader(String name, String value) {
      headers.add(name, value);
      return this;
    }

    /** Removes all headers named {@code name} on this builder. */
    public Builder removeHeader(String name) {
      headers.removeAll(name);
      return this;
    }

    /** Removes all headers on this builder and adds {@code headers}. */
    public Builder headers(Headers headers) {
      this.headers = headers.newBuilder();
      return this;
    }

    public Builder body(ResponseBody body) {
      this.body = body;
      return this;
    }

    public Builder sentRequestAtMillis(long sentRequestAtMillis) {
      this.sentRequestAtMillis = sentRequestAtMillis;
      return this;
    }

    public Builder receivedResponseAtMillis(long receivedResponseAtMillis) {
      this.receivedResponseAtMillis = receivedResponseAtMillis;
      return this;
    }

    public void initExchange(Exchange deferredTrailers) {
      this.exchange = deferredTrailers;
    }

    public Response build() {
      if (request == null) throw new IllegalStateException("request == null");
      if (protocol == null) throw new IllegalStateException("protocol == null");
      if (code < 0) throw new IllegalStateException("code < 0: " + code);
      if (message == null) throw new IllegalStateException("message == null");
      return new Response(this);
    }
  }
}
