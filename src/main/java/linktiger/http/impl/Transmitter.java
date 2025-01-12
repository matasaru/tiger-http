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
package linktiger.http.impl;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.net.Socket;
import java.util.logging.Level;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;

import linktiger.http.Address;
import linktiger.http.Call;
import linktiger.http.EventListener;
import linktiger.http.HttpClient;
import linktiger.http.Request;
import linktiger.http.Url;

import okio.AsyncTimeout;
import okio.Timeout;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Bridge between OkHttp's application and network layers. This class exposes high-level application
 * layer primitives: connections, requests, responses, and streams.
 *
 * <p>This class supports {@linkplain #cancel asynchronous canceling}. This is intended to have the
 * smallest blast radius possible.
 */
public final class Transmitter {
  private final HttpClient client;
  private final ConnectionPool connectionPool;
  private final Call call;
  private final EventListener eventListener;
  private final AsyncTimeout timeout = new AsyncTimeout() {
    @Override protected void timedOut() {
      cancel();
    }
  };

  private Object callStackTrace;

  private Request request;
  private ExchangeFinder exchangeFinder;

  // Guarded by connectionPool.
  public Connection connection;
  private Exchange exchange;
  private boolean exchangeRequestDone;
  private boolean exchangeResponseDone;
  private boolean canceled;
  private boolean noMoreExchanges;

  public Transmitter(HttpClient client, Call call) {
    this.client = client;
    this.connectionPool = client.connectionPool();
    this.call = call;
    this.eventListener = client.eventListenerFactory().create(call);
    this.timeout.timeout(client.callTimeoutMillis(), MILLISECONDS);
  }

  public Timeout timeout() {
    return timeout;
  }

  public void timeoutEnter() {
    timeout.enter();
  }

  private IOException timeoutExit(IOException cause) {
    if (!timeout.exit()) return cause;

    InterruptedIOException e = new InterruptedIOException("timeout");
    if (cause != null) e.initCause(cause);

    return e;
  }

  public void callStart() {
    if (HttpClient.logger.isLoggable(Level.FINE)) {
      // These are expensive to allocate
      this.callStackTrace = new Throwable("response.body().close()");
    }
    eventListener.callStart(call);
  }

  /**
   * Prepare to create a stream to carry {@code request}. This prefers to use the existing
   * connection if it exists.
   */
  public void prepareToConnect(Request request) {
    if (this.request != null) {
      if (Util.sameConnection(this.request.url(), request.url()) && exchangeFinder.hasRouteToTry()) {
        return; // Already ready.
      }
      if (exchange != null) throw new IllegalStateException();

      if (exchangeFinder != null) {
        maybeReleaseConnection(null, true);
        exchangeFinder = null;
      }
    }

    this.request = request;
    this.exchangeFinder = new ExchangeFinder(this, connectionPool, createAddress(request.url()),
        call, eventListener);
  }

  private Address createAddress(Url url) {
    SSLSocketFactory sslSocketFactory = null;
    HostnameVerifier hostnameVerifier = null;
    if (url.isHttps()) {
      sslSocketFactory = client.sslSocketFactory();
      hostnameVerifier = client.hostnameVerifier();
    }

    return new Address(url.host(), url.port(), client.dns(), client.socketFactory(),
        sslSocketFactory, hostnameVerifier, client.proxyAuthenticator(),
        client.proxy(), client.protocols(), client.connectionSpecs(), client.proxySelector());
  }

  /** Returns a new exchange to carry a new request and response. */
  public Exchange newExchange(boolean doExtensiveHealthChecks) {
    synchronized (connectionPool) {
      if (noMoreExchanges) {
        throw new IllegalStateException("released");
      }
      if (exchange != null) {
        throw new IllegalStateException("cannot make a new request because the previous response "
            + "is still open: please call response.close()");
      }
    }

    ExchangeCodec codec = exchangeFinder.find(client, doExtensiveHealthChecks);
    Exchange result = new Exchange(this, call, eventListener, exchangeFinder, codec);

    synchronized (connectionPool) {
      this.exchange = result;
      this.exchangeRequestDone = false;
      this.exchangeResponseDone = false;
      return result;
    }
  }

  void acquireConnectionNoEvents(Connection connection) {
    assert (Thread.holdsLock(connectionPool));

    if (this.connection != null) throw new IllegalStateException();
    this.connection = connection;
    connection.transmitters.add(new TransmitterReference(this, callStackTrace));
  }

  /**
   * Remove the transmitter from the connection's list of allocations. Returns a socket that the
   * caller should close.
   */
  Socket releaseConnectionNoEvents() {
    assert (Thread.holdsLock(connectionPool));

    int index = -1;
    for (int i = 0, size = this.connection.transmitters.size(); i < size; i++) {
      Reference<Transmitter> reference = this.connection.transmitters.get(i);
      if (reference.get() == this) {
        index = i;
        break;
      }
    }

    if (index == -1) throw new IllegalStateException();

    Connection released = this.connection;
    released.transmitters.remove(index);
    this.connection = null;

    if (released.transmitters.isEmpty()) {
      released.idleAtNanos = System.nanoTime();
      if (connectionPool.connectionBecameIdle(released)) {
        return released.socket();
      }
    }

    return null;
  }

  public void exchangeDoneDueToException() {
    synchronized (connectionPool) {
      if (noMoreExchanges) throw new IllegalStateException();
      exchange = null;
    }
  }

  /**
   * Releases resources held with the request or response of {@code exchange}. This should be called
   * when the request completes normally or when it fails due to an exception, in which case {@code
   * e} should be non-null.
   *
   * <p>If the exchange was canceled or timed out, this will wrap {@code e} in an exception that
   * provides that additional context. Otherwise {@code e} is returned as-is.
   */
  IOException exchangeMessageDone(
      Exchange exchange, boolean requestDone, boolean responseDone, IOException e) {
    boolean exchangeDone = false;
    synchronized (connectionPool) {
      if (exchange != this.exchange) {
        return e; // This exchange was detached violently!
      }
      boolean changed = false;
      if (requestDone) {
        if (!exchangeRequestDone) changed = true;
        this.exchangeRequestDone = true;
      }
      if (responseDone) {
        if (!exchangeResponseDone) changed = true;
        this.exchangeResponseDone = true;
      }
      if (exchangeRequestDone && exchangeResponseDone && changed) {
        exchangeDone = true;
        this.exchange.connection().successCount++;
        this.exchange = null;
      }
    }
    if (exchangeDone) {
      e = maybeReleaseConnection(e, false);
    }
    return e;
  }

  public IOException noMoreExchanges(IOException e) {
    synchronized (connectionPool) {
      noMoreExchanges = true;
    }
    return maybeReleaseConnection(e, false);
  }

  /**
   * Release the connection if it is no longer needed. This is called after each exchange completes
   * and after the call signals that no more exchanges are expected.
   *
   * <p>If the transmitter was canceled or timed out, this will wrap {@code e} in an exception that
   * provides that additional context. Otherwise {@code e} is returned as-is.
   *
   * @param force true to release the connection even if more exchanges are expected for the call.
   */
  private IOException maybeReleaseConnection(IOException e, boolean force) {
    Socket socket;
    Connection releasedConnection;
    boolean callEnd;
    synchronized (connectionPool) {
      if (force && exchange != null) {
        throw new IllegalStateException("cannot release connection while it is in use");
      }
      releasedConnection = this.connection;
      socket = this.connection != null && exchange == null && (force || noMoreExchanges)
          ? releaseConnectionNoEvents()
          : null;
      if (this.connection != null) releasedConnection = null;
      callEnd = noMoreExchanges && exchange == null;
    }
    if (socket != null) {
      try {
        socket.close();
      } catch (IOException _) {
      }
    }

    if (releasedConnection != null) {
      eventListener.connectionReleased(call, releasedConnection);
    }

    if (callEnd) {
      boolean callFailed = (e != null);
      e = timeoutExit(e);
      if (callFailed) {
        eventListener.callFailed(call, e);
      } else {
        eventListener.callEnd(call);
      }
    }
    return e;
  }

  public boolean canRetry() {
    return exchangeFinder.hasStreamFailure() && exchangeFinder.hasRouteToTry();
  }

  /**
   * Immediately closes the socket connection if it's currently held. Use this to interrupt an
   * in-flight request from any thread. It's the caller's responsibility to close the request body
   * and response body streams; otherwise resources may be leaked.
   *
   * <p>This method is safe to be called concurrently, but provides limited guarantees.
   */
  public void cancel() {
    Exchange exchangeToCancel;
    Connection connectionToCancel;
    synchronized (connectionPool) {
      canceled = true;
      exchangeToCancel = exchange;
      connectionToCancel = exchangeFinder != null && exchangeFinder.connectingConnection() != null
          ? exchangeFinder.connectingConnection()
          : connection;
    }
    if (exchangeToCancel != null) {
      exchangeToCancel.cancel();
    } else if (connectionToCancel != null) {
      connectionToCancel.cancel();
    }
  }

  public boolean isCanceled() {
    synchronized (connectionPool) {
      return canceled;
    }
  }

  static final class TransmitterReference extends WeakReference<Transmitter> {
    /**
     * Captures the stack trace at the time the Call is executed or enqueued. This is helpful for
     * identifying the origin of connection leaks.
     */
    final Object callStackTrace;

    TransmitterReference(Transmitter referent, Object callStackTrace) {
      super(referent);
      this.callStackTrace = callStackTrace;
    }
  }
}
