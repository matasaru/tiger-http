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
import java.util.ArrayList;
import java.util.List;

import linktiger.http.impl.CallServerInterceptor;
import linktiger.http.impl.Interceptor;
import linktiger.http.impl.InterceptorChain;
import linktiger.http.impl.RetryAndFollowUpInterceptor;
import linktiger.http.impl.Transmitter;

import okio.Timeout;

/**
 * A call is a request that has been prepared for execution. A call can be canceled. As this object
 * represents a single request/response pair (stream), it cannot be executed twice.
 */
public class Call implements Cloneable {
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
    call.transmitter = new Transmitter(client, call);
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
      return getResponseWithInterceptorChain();
    } finally {
      client.finished(this);
    }
  }

  /** Cancels the request, if possible. Requests that are already complete cannot be canceled. */
  public void cancel() {
    transmitter.cancel();
  }

  /**
   * Returns true if this call has been {@linkplain #execute() executed}.
   * It is an error to execute a call more than once.
   */
  public synchronized boolean isExecuted() {
    return executed;
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
   * Create a new, identical call to this one which can be enqueued or executed even if this call
   * has already been.
   */
  @SuppressWarnings("CloneDoesntCallSuperClone") // We are a final type & this saves clearing state.
  @Override public Call clone() {
    return Call.newCall(client, originalRequest);
  }

  /**
   * Returns a string that describes this call. Doesn't include a full URL as that might contain
   * sensitive information.
   */
  String toLoggableString() {
    return (isCanceled() ? "canceled " : "")
            + "call to " + redactedUrl();
  }

  String redactedUrl() {
    return originalRequest.url().redact();
  }

  Response getResponseWithInterceptorChain() throws IOException {
    // Build a full stack of interceptors.
    List<Interceptor> interceptors = new ArrayList<>();
    interceptors.add(new RetryAndFollowUpInterceptor(client));
    interceptors.add(new CallServerInterceptor());

    InterceptorChain chain = new InterceptorChain(interceptors, transmitter, null, 0,
            originalRequest, this, client.connectTimeoutMillis(),
            client.readTimeoutMillis(), client.writeTimeoutMillis());

    boolean calledNoMoreExchanges = false;
    try {
      Response response = chain.proceed(originalRequest);
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
  }
}
