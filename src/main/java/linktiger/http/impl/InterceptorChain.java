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
package linktiger.http.impl;

import java.io.IOException;
import java.util.List;

import linktiger.http.Call;
import linktiger.http.Request;
import linktiger.http.Response;

/**
 * A concrete interceptor chain that carries the entire interceptor chain: all application
 * interceptors, the OkHttp core, all network interceptors, and finally the network caller.
 *
 * <p>If the chain is for an application interceptor then {@link #connection} must be null.
 * Otherwise it is for a network interceptor and {@link #connection} must be non-null.
 */
public final class InterceptorChain {
  private final List<Interceptor> interceptors;
  private final Transmitter transmitter;
  private final Exchange exchange;
  private final int index;
  private final Request request;
  private final Call call;
  private final int connectTimeout;
  private final int readTimeout;
  private final int writeTimeout;

  public InterceptorChain(List<Interceptor> interceptors, Transmitter transmitter,
      Exchange exchange, int index, Request request, Call call,
      int connectTimeout, int readTimeout, int writeTimeout) {
    this.interceptors = interceptors;
    this.transmitter = transmitter;
    this.exchange = exchange;
    this.index = index;
    this.request = request;
    this.call = call;
    this.connectTimeout = connectTimeout;
    this.readTimeout = readTimeout;
    this.writeTimeout = writeTimeout;
  }

  /**
   * Returns the connection the request will be executed on. This is only available in the chains
   * of network interceptors; for application interceptors this is always null.
   */
  public Connection connection() {
    return exchange != null ? exchange.connection() : null;
  }

  public int connectTimeoutMillis() {
    return connectTimeout;
  }

  public int readTimeoutMillis() {
    return readTimeout;
  }

  public int writeTimeoutMillis() {
    return writeTimeout;
  }

  public Transmitter transmitter() {
    return transmitter;
  }

  public Exchange exchange() {
    if (exchange == null) throw new IllegalStateException();
    return exchange;
  }

  public Call call() {
    return call;
  }

  public Request request() {
    return request;
  }

  public Response proceed(Request request) throws IOException {
    if (index >= interceptors.size()) throw new AssertionError();

    // Call the next interceptor in the chain.
    InterceptorChain next = new InterceptorChain(interceptors, transmitter, exchange,
        index + 1, request, call, connectTimeout, readTimeout, writeTimeout);
    Interceptor interceptor = interceptors.get(index);
    Response response = interceptor.intercept(next);

    // Confirm that the intercepted response isn't null.
    if (response == null) {
      throw new NullPointerException("interceptor " + interceptor + " returned null");
    }

    if (response.body() == null) {
      throw new IllegalStateException(
          "interceptor " + interceptor + " returned a response with no body");
    }

    return response;
  }
}
