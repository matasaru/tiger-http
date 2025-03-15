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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.util.List;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

import kio.BufferedSink;
import kio.GzipSource;
import kio.RealBufferedSink;
import kio.RealBufferedSource;
import kio.Timeout;

/**
 * A call is a request that has been prepared for execution. A call can be canceled. As this object
 * represents a single request/response pair (stream), it cannot be executed twice.
 */
public class Call {

  final HttpClient client;

  /**
   * There is a cycle between the {@link Call} and {@link Transmitter} that makes this awkward.
   * This is set after immediately after creating the call instance.
   */
  private Transmitter transmitter;

  /** The application's original request unadulterated by redirects or auth headers. */
  final Request originalRequest;

  // Guarded by this.
  private boolean executed;

  private Call(HttpClient client, Request originalRequest) {
    this.client = client;
    this.originalRequest = originalRequest;
  }

  public static Call newCall(HttpClient client, Request originalRequest) {
    // Safely publish the Call instance to the EventListener.
    Call call = new Call(client, originalRequest);
    call.transmitter = new Transmitter(client);
    return call;
  }

  /** Returns the original request that initiated this call. */
  public Request request() {
    return originalRequest;
  }

  /**
   * Invokes the request immediately, and blocks until the response can be processed or is in
   * error.
   *
   * <p>To avoid leaking resources callers should close the {@link Response} which in turn will
   * close the underlying {@link ResponseBody}.
   *
   * <pre>{@code
   *
   *   // ensure the response (and underlying response body) is closed
   *   try (Response response = client.newCall(request).execute()) {
   *     ...
   *   }
   *
   * }</pre>
   *
   * <p>The caller may read the response body with the response's {@link Response#body} method. To
   * avoid leaking resources callers must {@linkplain ResponseBody close the response body} or the
   * Response.
   *
   * <p>Note that transport-layer success (receiving a HTTP response code, headers and body) does
   * not necessarily indicate application-layer success: {@code response} may still indicate an
   * unhappy HTTP response code like 404 or 500.
   *
   * @throws IOException if the request could not be executed due to cancellation, a connectivity
   * problem or timeout. Because networks can fail during an exchange, it is possible that the
   * remote server accepted the request before the failure.
   * @throws IllegalStateException when the call has already been executed.
   */
  public Response execute() throws IOException {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    transmitter.timeoutEnter();
    transmitter.callStart();
    try {
      client.executed(this);

      boolean calledNoMoreExchanges = false;
      try {
        Request request = originalRequest;
        Response response;
        while (true) {
          transmitter.prepareToConnect(request);

          if (transmitter.isCanceled()) {
            throw new IOException("Canceled");
          }

          boolean success = false;
          try {
            Request.Builder requestBuilder = request.newBuilder();

            RequestBody body = request.body();
            if (body != null) {
              MediaType contentType = body.contentType();
              if (contentType != null) {
                requestBuilder.header("Content-Type", contentType.toString());
              }

              long contentLength = body.contentLength();
              if (contentLength != -1) {
                requestBuilder.header("Content-Length", Long.toString(contentLength));
                requestBuilder.removeHeader("Transfer-Encoding");
              }
              else {
                requestBuilder.header("Transfer-Encoding", "chunked");
                requestBuilder.removeHeader("Content-Length");
              }
            }

            if (request.header("Host") == null) {
              requestBuilder.header("Host", Util.hostHeader(request.url(), false));
            }

            if (request.header("Connection") == null) {
              requestBuilder.header("Connection", "Keep-Alive");
            }

            boolean transparentGzip = false;
            if (request.header("Accept-Encoding") == null && request.header("Range") == null) {
              transparentGzip = true;
              requestBuilder.header("Accept-Encoding", "gzip");
            }

            List<Cookie> cookies =  client.cookieJar().loadForRequest(request.url());
            if (!cookies.isEmpty()) {
              requestBuilder.header("Cookie", cookieHeader(cookies));
            }

            if (request.header("User-Agent") == null) {
              requestBuilder.header("User-Agent", Version.userAgent());
            }

            Request networkRequest = requestBuilder.build();
            // We need the network to satisfy this request. Possibly for validating a conditional GET.
            boolean doExtensiveHealthChecks = !networkRequest.method().equals("GET");
            Exchange exchange = transmitter.newExchange(doExtensiveHealthChecks);

            long sentRequestMillis = System.currentTimeMillis();

            exchange.writeRequestHeaders(networkRequest);

            if (!(networkRequest.method().equals("GET") || networkRequest.method().equals("HEAD")) && networkRequest.body() != null) {
              BufferedSink bufferedRequestBody = new RealBufferedSink(exchange.createRequestBody(networkRequest));
              networkRequest.body().writeTo(bufferedRequestBody);
              bufferedRequestBody.close();
            }
            else {
              exchange.noRequestBody();
            }

            exchange.finishRequest();

            var networkResponseBuilder = exchange.readResponseHeaders();

            Response networkResponse = networkResponseBuilder
                    .request(networkRequest)
                    .handshake(exchange.connection().handshake())
                    .sentRequestAtMillis(sentRequestMillis)
                    .receivedResponseAtMillis(System.currentTimeMillis())
                    .build();

            networkResponse = networkResponse.newBuilder()
                    .body(exchange.openResponseBody(networkResponse))
                    .build();

            if ("close".equalsIgnoreCase(networkResponse.request().header("Connection")) ||
                "close".equalsIgnoreCase(networkResponse.header("Connection"))) {
              exchange.noNewExchangesOnConnection();
            }

            HttpHeaders.receiveHeaders(client.cookieJar(), request.url(), networkResponse.headers());

            Response.Builder responseBuilder = networkResponse.newBuilder()
                    .request(request);

            if (transparentGzip && "gzip".equalsIgnoreCase(networkResponse.header("Content-Encoding")) && networkResponse.hasBody()) {
              GzipSource responseBody = new GzipSource(networkResponse.body().source());
              Headers strippedHeaders = networkResponse.headers().newBuilder()
                      .removeAll("Content-Encoding")
                      .removeAll("Content-Length")
                      .build();
              responseBuilder.headers(strippedHeaders);
              String contentType = networkResponse.header("Content-Type");
              responseBuilder.body(new ResponseBody(contentType, -1L, new RealBufferedSource(responseBody)));
            }

            response = responseBuilder.build();

            success = true;
          }
          catch (RouteException e) {
            // The attempt to connect via a route failed. The request will not have been sent.
            if (!recover(e.getLastConnectException(), transmitter, false)) {
              throw e.getFirstConnectException();
            }
            continue;
          }
          catch (IOException e) {
            // An attempt to communicate with a server failed. The request may have been sent.
              if (!recover(e, transmitter, true)) throw e;
            continue;
          }
          finally {
            // The network call threw an exception. Release any resources.
            if (!success) {
              transmitter.exchangeDoneDueToException();
            }
          }

          break;
        } // while

        if (transmitter.isCanceled()) {
          response.close();
          throw new IOException("Canceled");
        }
        return response;
      } catch (IOException e) {
        calledNoMoreExchanges = true;
        throw transmitter.noMoreExchanges(e);
      } finally {
        if (!calledNoMoreExchanges) {
          transmitter.noMoreExchanges(null);
        }
      }
    } finally {
      client.finished(this);
    }
  }

  /** Cancels the request, if possible. Requests that are already complete cannot be canceled. */
  public void cancel() {
    transmitter.cancel();
  }

  public boolean isCanceled() {
    return transmitter.isCanceled();
  }

  /**
   * Returns a timeout that spans the entire call: resolving DNS, connecting, writing the request
   * body, server processing, and reading the response body. If the call requires redirects or
   * retries all must complete within one timeout period.
   *
   * <p>Configure the client's default timeout with {@link HttpClient.Builder#callTimeout}.
   */
  public Timeout timeout() {
    return transmitter.timeout();
  }

  /**
   * Report and attempt to recover from a failure to communicate with a server. Returns true if
   * {@code e} is recoverable, or false if the failure is permanent. Requests with a body can only
   * be recovered if the body is buffered or if the failure occurred before the request has been
   * sent.
   */
  private boolean recover(IOException e, Transmitter transmitter, boolean requestSendStarted) {
    // The application layer has forbidden retries.
    if (!client.retryOnConnectionFailure()) return false;

    // We can't send the request body again.
    if (requestSendStarted && e instanceof FileNotFoundException) return false;

    // This exception is fatal.
    if (!isRecoverable(e, requestSendStarted)) return false;

    // No more routes to attempt.
    if (!transmitter.canRetry()) return false;

    // For failure recovery, use the same route selector with a new connection.
    return true;
  }

  private boolean isRecoverable(IOException e, boolean requestSendStarted) {
    // If there was a protocol problem, don't recover.
    if (e instanceof ProtocolException) {
      return false;
    }

    // If there was an interruption don't recover, but if there was a timeout connecting to a route
    // we should try the next route (if there is one).
    if (e instanceof InterruptedIOException) {
      return e instanceof SocketTimeoutException && !requestSendStarted;
    }

    // Look for known client-side or negotiation errors that are unlikely to be fixed by trying
    // again with a different route.
    if (e instanceof SSLHandshakeException) {
      // If the problem was a CertificateException from the X509TrustManager,
      // do not retry.
      if (e.getCause() instanceof CertificateException) {
        return false;
      }
    }

    if (e instanceof SSLPeerUnverifiedException) {
      return false;
    }

    // An example of one we might want to retry with a different route is a problem connecting to a
    // proxy and would manifest as a standard IOException. Unless it is one we know we should not
    // retry, we return true and try a new route.
    return true;
  }

  private String cookieHeader(List<Cookie> cookies) {
    StringBuilder cookieHeader = new StringBuilder();
    for (int i = 0, size = cookies.size(); i < size; i++) {
      if (i > 0) {
        cookieHeader.append("; ");
      }
      Cookie cookie = cookies.get(i);
      cookieHeader.append(cookie.name()).append('=').append(cookie.value());
    }
    return cookieHeader.toString();
  }
}
