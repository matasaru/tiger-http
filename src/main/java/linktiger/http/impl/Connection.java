/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package linktiger.http.impl;

import java.io.IOException;
import java.lang.ref.Reference;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownServiceException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import linktiger.http.Address;
import linktiger.http.Call;
import linktiger.http.ConnectionSpec;
import linktiger.http.EventListener;
import linktiger.http.Handshake;
import linktiger.http.HttpClient;
import linktiger.http.Protocol;
import linktiger.http.Request;
import linktiger.http.Response;
import linktiger.http.ResponseBody;
import linktiger.http.Route;
import linktiger.http.Url;

import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * The sockets and streams of an HTTP or HTTPS connection. May be used for multiple
 * HTTP request/response exchanges. Connections may be direct to the origin server or via a proxy.
 *
 * <p>Typically instances of this class are created, connected and exercised automatically by the
 * HTTP client. Applications may use this class to monitor HTTP connections as members of a
 * {@linkplain ConnectionPool connection pool}.
 *
 * <p>Do not confuse this class with the misnamed {@code HttpURLConnection}, which isn't so much a
 * connection as a single request/response exchange.
 *
 * <h3>Modern TLS</h3>
 *
 * <p>There are tradeoffs when selecting which options to include when negotiating a secure
 * connection to a remote host. Newer TLS offers options such as Server Name Indication (SNI)
 * which enables one IP address to negotiate secure connections for multiple domain names.
 *
 * <p>Unfortunately, older HTTPS servers refuse to connect when such options are presented. Rather
 * than avoiding these options entirely, this class allows a connection to be attempted with modern
 * options and then retried without them should the attempt fail.
 *
 * <h3>Connection Reuse</h3>
 *
 * <p>Each connection can carry a either zero or one streams. A connection currently carrying zero
 * streams is an idle stream. We keep it alive because reusing an existing connection is typically
 * faster than establishing a new one.
 * TODO review the text below
 * <p>When a single logical call requires multiple streams due to redirects or authorization
 * challenges, we prefer to use the same physical connection for all streams in the sequence. There
 * are potential performance and behavior consequences to this preference. To support this feature,
 * this class separates <i>allocations</i> from <i>streams</i>. An allocation is created by a call,
 * used for one or more streams, and then released. An allocated connection won't be stolen by other
 * calls while a redirect or authorization challenge is being handled.
 *
 * <p>When the maximum concurrent streams limit is reduced, some allocations will be rescinded.
 * Attempting to create new streams on these allocations will fail.
 *
 * <p>Note that an allocation may be released before its stream is completed. This is intended to
 * make bookkeeping easier for the caller: releasing the allocation as soon as the terminal stream
 * has been found. But only complete the stream once its data stream has been exhausted.
 */
public final class Connection {

  private static final int MAX_TUNNEL_ATTEMPTS = 21;

  public final ConnectionPool connectionPool;
  private final Route route;

  // The fields below are initialized by connect() and never reassigned.

  /** The low-level TCP socket. */
  private Socket rawSocket;

  /**
   * The application layer socket. Either an {@link SSLSocket} layered over {@link #rawSocket}, or
   * {@link #rawSocket} itself if this connection does not use SSL.
   */
  private Socket socket;
  private Handshake handshake;
  private Protocol protocol;
  private BufferedSource source;
  private BufferedSink sink;

  // The fields below track connection state and are guarded by connectionPool.

  /**
   * If true, no new exchanges can be created on this connection. Once true this is always true.
   * Guarded by {@link #connectionPool}.
   */
  boolean noNewExchanges;

  /**
   * The number of times there was a problem establishing a stream that could be due to route
   * chosen. Guarded by {@link #connectionPool}.
   */
  int routeFailureCount;

  int successCount;

  // TODO no need for list - there can be at most one transmitter
  /** Current calls carried by this connection. */
  final List<Reference<Transmitter>> transmitters = new ArrayList<>();

  /** Nanotime timestamp when {@code allocations.size()} reached zero. */
  long idleAtNanos = Long.MAX_VALUE;

  public Connection(ConnectionPool connectionPool, Route route) {
    this.connectionPool = connectionPool;
    this.route = route;
  }

  /** Prevent further exchanges from being created on this connection. */
  public void noNewExchanges() {
    assert (!Thread.holdsLock(connectionPool));
    synchronized (connectionPool) {
      noNewExchanges = true;
    }
  }

  static Connection testConnection(
      ConnectionPool connectionPool, Route route, Socket socket, long idleAtNanos) {
    Connection result = new Connection(connectionPool, route);
    result.socket = socket;
    result.idleAtNanos = idleAtNanos;
    return result;
  }

  public void connect(int connectTimeout, int readTimeout, int writeTimeout,
      boolean connectionRetryEnabled, Call call,
      EventListener eventListener) {
    if (protocol != null) throw new IllegalStateException("already connected");

    RouteException routeException = null;
    List<ConnectionSpec> connectionSpecs = route.address().connectionSpecs();
    ConnectionSpecSelector connectionSpecSelector = new ConnectionSpecSelector(connectionSpecs);

    if (route.address().sslSocketFactory() == null) {
      if (!connectionSpecs.contains(ConnectionSpec.CLEARTEXT)) {
        throw new RouteException(new UnknownServiceException(
            "CLEARTEXT communication not enabled for client"));
      }
    }

    while (true) {
      try {
        if (route.requiresTunnel()) {
          connectTunnel(connectTimeout, readTimeout, writeTimeout, call, eventListener);
          if (rawSocket == null) {
            // We were unable to connect the tunnel but properly closed down our resources.
            break;
          }
        } else {
          connectSocket(connectTimeout, readTimeout, call, eventListener);
        }
        establishProtocol(connectionSpecSelector, call, eventListener);
        eventListener.connectEnd(call, route.socketAddress(), route.proxy(), protocol);
        break;
      } catch (IOException e) {
        if (socket != null) {
          try {
            socket.close();
          } catch (IOException _) {
          }
        }
        if (rawSocket != null) {
          try {
            rawSocket.close();
          } catch (IOException _) {
          }
        }
        socket = null;
        rawSocket = null;
        source = null;
        sink = null;
        handshake = null;
        protocol = null;

        eventListener.connectFailed(call, route.socketAddress(), route.proxy(), null, e);

        if (routeException == null) {
          routeException = new RouteException(e);
        } else {
          routeException.addConnectException(e);
        }

        if (!connectionRetryEnabled || !connectionSpecSelector.connectionFailed(e)) {
          throw routeException;
        }
      }
    }

    if (route.requiresTunnel() && rawSocket == null) {
      ProtocolException exception = new ProtocolException("Too many tunnel connections attempted: "
          + MAX_TUNNEL_ATTEMPTS);
      throw new RouteException(exception);
    }
  }

  /**
   * Does all the work to build an HTTPS connection over a proxy tunnel. The catch here is that a
   * proxy server can issue an auth challenge and then close the connection.
   */
  private void connectTunnel(int connectTimeout, int readTimeout, int writeTimeout, Call call,
      EventListener eventListener) throws IOException {
    Request tunnelRequest = createTunnelRequest();
    Url url = tunnelRequest.url();
    for (int i = 0; i < MAX_TUNNEL_ATTEMPTS; i++) {
      connectSocket(connectTimeout, readTimeout, call, eventListener);
      tunnelRequest = createTunnel(readTimeout, writeTimeout, tunnelRequest, url);

      if (tunnelRequest == null) break; // Tunnel successfully created.

      // The proxy decided to close the connection after an auth challenge. We need to create a new
      // connection, but this time with the auth credentials.
      if (rawSocket != null) {
        try {
          rawSocket.close();
        } catch (IOException _) {
        }
      }
      rawSocket = null;
      sink = null;
      source = null;
      eventListener.connectEnd(call, route.socketAddress(), route.proxy(), null);
    }
  }

  /** Does all the work necessary to build a full HTTP or HTTPS connection on a raw socket. */
  private void connectSocket(int connectTimeout, int readTimeout, Call call,
      EventListener eventListener) throws IOException {
    Proxy proxy = route.proxy();
    Address address = route.address();

    rawSocket = proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.HTTP
        ? address.socketFactory().createSocket()
        : new Socket(proxy);

    eventListener.connectStart(call, route.socketAddress(), proxy);
    rawSocket.setSoTimeout(readTimeout);
    try {
      rawSocket.connect(route.socketAddress(), connectTimeout);
    } catch (ConnectException e) {
      ConnectException ce = new ConnectException("Failed to connect to " + route.socketAddress());
      ce.initCause(e);
      throw ce;
    }

    source = Okio.buffer(Okio.source(rawSocket));
    sink = Okio.buffer(Okio.sink(rawSocket));
  }

  private void establishProtocol(ConnectionSpecSelector connectionSpecSelector, Call call, EventListener eventListener) throws IOException {
    if (route.address().sslSocketFactory() == null) {
      socket = rawSocket;
      protocol = Protocol.HTTP_1_1;
      return;
    }

    eventListener.secureConnectStart(call);
    connectTls(connectionSpecSelector);
    eventListener.secureConnectEnd(call, handshake);
  }

  private void connectTls(ConnectionSpecSelector connectionSpecSelector) throws IOException {
    Address address = route.address();
    SSLSocketFactory sslSocketFactory = address.sslSocketFactory();
    boolean success = false;
    SSLSocket sslSocket = null;
    try {
      // Create the wrapper over the connected socket.
      sslSocket = (SSLSocket) sslSocketFactory.createSocket(
          rawSocket, address.url().host(), address.url().port(), true /* autoClose */);

      // Configure the socket's ciphers, TLS versions, and extensions.
      connectionSpecSelector.configureSecureSocket(sslSocket);

      SSLParameters sslParameters = sslSocket.getSSLParameters();
      sslParameters.setApplicationProtocols(new String[]{Protocol.HTTP_1_1.toString()});

      sslSocket.setSSLParameters(sslParameters);

      // Force handshake. This can throw!
      sslSocket.startHandshake();
      // block for session establishment
      SSLSession sslSocketSession = sslSocket.getSession();
      Handshake unverifiedHandshake = Handshake.get(sslSocketSession);

      // Verify that the socket's certificates are acceptable for the target host.
      if (!address.hostnameVerifier().verify(address.url().host(), sslSocketSession)) {
        List<Certificate> peerCertificates = unverifiedHandshake.peerCertificates();
        if (!peerCertificates.isEmpty()) {
          X509Certificate cert = (X509Certificate) peerCertificates.get(0);
          throw new SSLPeerUnverifiedException(
              "Hostname " + address.url().host() + " not verified:"
                  + "\n    DN: " + cert.getSubjectX500Principal().getName()
                  + "\n    subjectAltNames: " + OkHostnameVerifier.allSubjectAltNames(cert));
        } else {
          throw new SSLPeerUnverifiedException(
              "Hostname " + address.url().host() + " not verified (no certificates)");
        }
      }

      // Success! Save the handshake and the ALPN protocol.
      socket = sslSocket;
      source = Okio.buffer(Okio.source(socket));
      sink = Okio.buffer(Okio.sink(socket));
      handshake = unverifiedHandshake;
      protocol = Protocol.HTTP_1_1;
      String maybeProtocol = sslSocket.getApplicationProtocol();
      // SSLSocket.getApplicationProtocol returns "" if application protocols values will not be used.
      // Observed if you didn't specify SSLParameters.setApplicationProtocols.
      if (maybeProtocol != null && !maybeProtocol.isEmpty()) {
        protocol = Protocol.get(maybeProtocol);
      }

      success = true;
    } finally {
      if (!success && sslSocket != null) {
        try {
          sslSocket.close();
        } catch (IOException _) {
        }
      }
    }
  }

  /**
   * To make an HTTPS connection over an HTTP proxy, send an unencrypted CONNECT request to create
   * the proxy connection. This may need to be retried if the proxy requires authorization.
   */
  private Request createTunnel(int readTimeout, int writeTimeout, Request tunnelRequest,
      Url url) throws IOException {
    // Make an SSL Tunnel on the first message pair of each SSL + proxy connection.
    String requestLine = "CONNECT " + Util.hostHeader(url, true) + " HTTP/1.1";
    while (true) {
      Http1ExchangeCodec tunnelCodec = new Http1ExchangeCodec(null, null, source, sink);
      source.timeout().timeout(readTimeout, MILLISECONDS);
      sink.timeout().timeout(writeTimeout, MILLISECONDS);
      tunnelCodec.writeRequest(tunnelRequest.headers(), requestLine);
      tunnelCodec.finishRequest();
      Response response = tunnelCodec.readResponseHeaders(false)
          .request(tunnelRequest)
          .build();
      tunnelCodec.skipConnectBody(response);

      switch (response.code()) {
        case HTTP_OK:
          // Assume the server won't send a TLS ServerHello until we send a TLS ClientHello. If
          // that happens, then we will have buffered bytes that are needed by the SSLSocket!
          // This check is imperfect: it doesn't tell us whether a handshake will succeed, just
          // that it will almost certainly fail because the proxy has sent unexpected data.
          if (!source.getBuffer().exhausted() || !sink.buffer().exhausted()) {
            throw new IOException("TLS tunnel buffered too many bytes!");
          }
          return null;

        case HTTP_PROXY_AUTH:
          tunnelRequest = route.address().proxyAuthenticator().authenticate(route, response);
          if (tunnelRequest == null) throw new IOException("Failed to authenticate with proxy");

          if ("close".equalsIgnoreCase(response.header("Connection"))) {
            return tunnelRequest;
          }
          break;

        default:
          throw new IOException(
              "Unexpected response code for CONNECT: " + response.code());
      }
    }
  }

  /**
   * Returns a request that creates a TLS tunnel via an HTTP proxy. Everything in the tunnel request
   * is sent unencrypted to the proxy server, so tunnels include only the minimum set of headers.
   * This avoids sending potentially sensitive data like HTTP cookies to the proxy unencrypted.
   *
   * <p>In order to support preemptive authentication we pass a fake “Auth Failed” response to the
   * authenticator. This gives the authenticator the option to customize the CONNECT request. It can
   * decline to do so by returning null, in which case OkHttp will use it as-is
   */
  private Request createTunnelRequest() throws IOException {
    Request proxyConnectRequest = new Request.Builder()
        .url(route.address().url())
        .method("CONNECT", null)
        .header("Host", Util.hostHeader(route.address().url(), true))
        .header("Proxy-Connection", "Keep-Alive") // For HTTP/1.0 proxies like Squid.
        .header("User-Agent", Version.userAgent())
        .build();

    Response fakeAuthChallengeResponse = new Response.Builder()
        .request(proxyConnectRequest)
        .protocol(Protocol.HTTP_1_1)
        .code(HttpURLConnection.HTTP_PROXY_AUTH)
        .message("Preemptive Authenticate")
        .body(ResponseBody.EMPTY)
        .sentRequestAtMillis(-1L)
        .receivedResponseAtMillis(-1L)
        .header("Proxy-Authenticate", "OkHttp-Preemptive")
        .build();

    Request authenticatedRequest = route.address().proxyAuthenticator()
        .authenticate(route, fakeAuthChallengeResponse);

    return authenticatedRequest != null
        ? authenticatedRequest
        : proxyConnectRequest;
  }

  /**
   * Returns true if this connection can carry a stream allocation to {@code address}. If non-null
   * {@code route} is the resolved route for a connection.
   */
  boolean isEligible(Address address) {
    // If this connection is not accepting new exchanges, we're done.
    if (!transmitters.isEmpty() || noNewExchanges) return false;

    // If the non-host fields of the address don't overlap, we're done.
    if (!this.route.address().equalsNonHost(address)) return false;

    // If the host exactly matches, we're done: this connection can carry the address.
    if (address.url().host().equals(this.route().address().url().host())) {
      return true; // This connection is a perfect match.
    }

    return false;
  }

  ExchangeCodec newCodec(HttpClient client) throws SocketException {
    socket.setSoTimeout(client.readTimeoutMillis());
    source.timeout().timeout(client.readTimeoutMillis(), MILLISECONDS);
    sink.timeout().timeout(client.writeTimeoutMillis(), MILLISECONDS);
    return new Http1ExchangeCodec(client, this, source, sink);
  }

  /** Returns the route used by this connection. */
  public Route route() {
    return route;
  }

  public void cancel() {
    // Close the raw socket so we don't end up doing synchronous I/O.
    try {
      rawSocket.close();
    } catch (IOException _) {
    }
  }

  /**
   * Returns the socket that this connection is using. Returns an {@linkplain
   * javax.net.ssl.SSLSocket SSL socket} if this connection is HTTPS.
   */
  public Socket socket() {
    return socket;
  }

  /** Returns true if this connection is ready to host new streams. */
  public boolean isHealthy(boolean doExtensiveChecks) {
    if (socket.isClosed() || socket.isInputShutdown() || socket.isOutputShutdown()) {
      return false;
    }

    if (doExtensiveChecks) {
      try {
        int readTimeout = socket.getSoTimeout();
        try {
          socket.setSoTimeout(1);
          if (source.exhausted()) {
            return false; // Stream is exhausted; socket is closed.
          }
          return true;
        } finally {
          socket.setSoTimeout(readTimeout);
        }
      } catch (SocketTimeoutException ignored) {
        // Read timed out; socket is good.
      } catch (IOException e) {
        return false; // Couldn't read; socket is closed.
      }
    }

    return true;
  }

  /**
   * Returns the TLS handshake used to establish this connection, or null if the connection is not HTTPS.
   */
  public Handshake handshake() {
    return handshake;
  }

  /**
   * Track a failure using this connection. This may prevent both the connection and its route from
   * being used for future exchanges.
   */
  void trackFailure(IOException e) {
    assert (!Thread.holdsLock(connectionPool));
    synchronized (connectionPool) {
      noNewExchanges = true;

      // If this route hasn't completed a call, avoid it for new connections.
      if (successCount == 0) {
        if (e != null) {
          connectionPool.connectFailed(route, e);
        }
        routeFailureCount++;
      }
    }
  }

  /**
   * Returns the protocol negotiated by this connection, or {@link Protocol#HTTP_1_1} if no protocol
   * has been negotiated. This method returns {@link Protocol#HTTP_1_1} even if the remote peer is
   * using {@link Protocol#HTTP_1_0}.
   */
  public Protocol protocol() {
    return protocol;
  }

  @Override public String toString() {
    return "Connection{"
        + route.address().url().host() + ":" + route.address().url().port()
        + ", proxy="
        + route.proxy()
        + " hostAddress="
        + route.socketAddress()
        + " cipherSuite="
        + (handshake != null ? handshake.cipherSuite() : "none")
        + " protocol="
        + protocol
        + '}';
  }
}
