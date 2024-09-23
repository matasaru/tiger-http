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

import linktiger.http.Call;
import linktiger.http.HttpClient;
import linktiger.http.Request;
import linktiger.http.Response;

/**
 * A concrete interceptor chain that carries the entire interceptor chain: all application
 * interceptors, the OkHttp core, all network interceptors, and finally the network caller.
 */
public final class InterceptorChain {
  private final HttpClient client;
  private final Transmitter transmitter;
  private final Request request;
  private final Call call;
  private final int connectTimeout;
  private final int readTimeout;
  private final int writeTimeout;

  public InterceptorChain(HttpClient client, Transmitter transmitter,
      Request request, Call call,
      int connectTimeout, int readTimeout, int writeTimeout) {
    this.client = client;
    this.transmitter = transmitter;
    this.request = request;
    this.call = call;
    this.connectTimeout = connectTimeout;
    this.readTimeout = readTimeout;
    this.writeTimeout = writeTimeout;
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

  public Call call() {
    return call;
  }

  public Request request() {
    return request;
  }

  public Response proceed(Request request) throws IOException {
    InterceptorChain next = new InterceptorChain(client, transmitter,
         request, call, connectTimeout, readTimeout, writeTimeout);
    Interceptor interceptor = new RetryAndFollowUpInterceptor(client);

    return interceptor.intercept(next);
  }
}
