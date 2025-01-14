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
package linktiger.http;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static okhttp3.tls.internal.TlsUtil.newKeyManager;
import static okhttp3.tls.internal.TlsUtil.newTrustManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class ClientAuthTest {
  @Rule public final MockWebServer server = new MockWebServer();

  private HeldCertificate serverRootCa;
  private HeldCertificate serverIntermediateCa;
  private HeldCertificate serverCert;
  private HeldCertificate clientRootCa;
  private HeldCertificate clientIntermediateCa;
  private HeldCertificate clientCert;

  @Before
  public void setUp() {
    serverRootCa = new HeldCertificate.Builder()
        .serialNumber(1L)
        .certificateAuthority(1)
        .commonName("root")
        .addSubjectAlternativeName("root_ca.com")
        .build();
    serverIntermediateCa = new HeldCertificate.Builder()
        .signedBy(serverRootCa)
        .certificateAuthority(0)
        .serialNumber(2L)
        .commonName("intermediate_ca")
        .addSubjectAlternativeName("intermediate_ca.com")
        .build();

    serverCert = new HeldCertificate.Builder()
        .signedBy(serverIntermediateCa)
        .serialNumber(3L)
        .commonName("Local Host")
        .addSubjectAlternativeName(server.getHostName())
        .build();

    clientRootCa = new HeldCertificate.Builder()
        .serialNumber(1L)
        .certificateAuthority(1)
        .commonName("root")
        .addSubjectAlternativeName("root_ca.com")
        .build();
    clientIntermediateCa = new HeldCertificate.Builder()
        .signedBy(serverRootCa)
        .certificateAuthority(0)
        .serialNumber(2L)
        .commonName("intermediate_ca")
        .addSubjectAlternativeName("intermediate_ca.com")
        .build();

    clientCert = new HeldCertificate.Builder()
        .signedBy(clientIntermediateCa)
        .serialNumber(4L)
        .commonName("Jethro Willis")
        .addSubjectAlternativeName("jethrowillis.com")
        .build();
  }

  @Test public void clientAuthForWants() throws Exception {
    HttpClient client = buildClient(clientCert, clientIntermediateCa.certificate());

    SSLSocketFactory socketFactory = buildServerSslSocketFactory();

    server.useHttps(socketFactory, false);
    server.requestClientAuth();
    server.enqueue(new MockResponse().setBody("abc"));

    Call call = client.newCall(new Request.Builder().url(server.url("/").toString()).build());
    Response response = call.execute();
    assertThat(response.handshake().peerPrincipal()).isEqualTo(
        new X500Principal("CN=Local Host"));
    assertThat(response.handshake().localPrincipal()).isEqualTo(
        new X500Principal("CN=Jethro Willis"));
    assertThat(response.body().string()).isEqualTo("abc");
  }

  @Test public void clientAuthForNeeds() throws Exception {
    HttpClient client = buildClient(clientCert, clientIntermediateCa.certificate());

    SSLSocketFactory socketFactory = buildServerSslSocketFactory();

    server.useHttps(socketFactory, false);
    server.requireClientAuth();
    server.enqueue(new MockResponse().setBody("abc"));

    Call call = client.newCall(new Request.Builder().url(server.url("/").toString()).build());
    Response response = call.execute();
    assertThat(response.handshake().peerPrincipal()).isEqualTo(
        new X500Principal("CN=Local Host"));
    assertThat(response.handshake().localPrincipal()).isEqualTo(
        new X500Principal("CN=Jethro Willis"));
    assertThat(response.body().string()).isEqualTo("abc");
  }

  @Test public void clientAuthSkippedForNone() throws Exception {
    HttpClient client = buildClient(clientCert, clientIntermediateCa.certificate());

    SSLSocketFactory socketFactory = buildServerSslSocketFactory();

    server.useHttps(socketFactory, false);
    server.noClientAuth();
    server.enqueue(new MockResponse().setBody("abc"));

    Call call = client.newCall(new Request.Builder().url(server.url("/").toString()).build());
    Response response = call.execute();
    assertThat(response.handshake().peerPrincipal()).isEqualTo(
        new X500Principal("CN=Local Host"));
    assertThat(response.handshake().localPrincipal()).isNull();
    assertThat(response.body().string()).isEqualTo("abc");
  }

  @Test public void missingClientAuthSkippedForWantsOnly() throws Exception {
    HttpClient client = buildClient(null, clientIntermediateCa.certificate());

    SSLSocketFactory socketFactory = buildServerSslSocketFactory();

    server.useHttps(socketFactory, false);
    server.requestClientAuth();
    server.enqueue(new MockResponse().setBody("abc"));

    Call call = client.newCall(new Request.Builder().url(server.url("/").toString()).build());
    Response response = call.execute();
    assertThat(response.handshake().peerPrincipal()).isEqualTo(
        new X500Principal("CN=Local Host"));
    assertThat(response.handshake().localPrincipal()).isNull();
    assertThat(response.body().string()).isEqualTo("abc");
  }

  @Test public void commonNameIsNotTrusted() throws Exception {
    serverCert = new HeldCertificate.Builder()
        .signedBy(serverIntermediateCa)
        .serialNumber(3L)
        .commonName(server.getHostName())
        .addSubjectAlternativeName("different-host.com")
        .build();

    HttpClient client = buildClient(clientCert, clientIntermediateCa.certificate());

    SSLSocketFactory socketFactory = buildServerSslSocketFactory();

    server.useHttps(socketFactory, false);
    server.requireClientAuth();

    Call call = client.newCall(new Request.Builder().url(server.url("/").toString()).build());

    try {
      call.execute();
      fail();
    } catch (SSLPeerUnverifiedException expected) {
    }
  }

  private HttpClient buildClient(
      HeldCertificate heldCertificate, X509Certificate... intermediates) {
    HandshakeCertificates.Builder builder = new HandshakeCertificates.Builder()
        .addTrustedCertificate(serverRootCa.certificate());

    if (heldCertificate != null) {
      builder.heldCertificate(heldCertificate, intermediates);
    }

    HandshakeCertificates handshakeCertificates = builder.build();
    return TestUtil.defaultClient().newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .build();
  }

  private SSLSocketFactory buildServerSslSocketFactory() {
    try {
      X509KeyManager keyManager = newKeyManager(
          null, serverCert, serverIntermediateCa.certificate());
      X509TrustManager trustManager = newTrustManager(
          null, Arrays.asList(serverRootCa.certificate(), clientRootCa.certificate()));
      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(new KeyManager[] {keyManager}, new TrustManager[] {trustManager},
          new SecureRandom());
      return sslContext.getSocketFactory();
    } catch (GeneralSecurityException e) {
      throw new AssertionError(e);
    }
  }
}
