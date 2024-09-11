/*
 * Copyright (C) 2016 Google Inc.
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

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;

public class CipherSuiteTest {
  @Test public void nullCipherName() {
    try {
      CipherSuite.forJavaName(null);
      fail("Should have thrown");
    } catch (NullPointerException expected) {
    }
  }

  @Test public void hashCode_usesIdentityHashCode_legacyCase() {
    CipherSuite cs = CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5; // This one's javaName starts with "SSL_".
    assertThat(cs.hashCode()).overridingErrorMessage(cs.toString()).isEqualTo(
        System.identityHashCode(cs));
  }

  @Test public void hashCode_usesIdentityHashCode_regularCase() {
    CipherSuite cs = CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256; // This one's javaName matches the identifier.
    assertThat(cs.hashCode()).overridingErrorMessage(cs.toString()).isEqualTo(
        System.identityHashCode(cs));
  }

  @Test public void instancesAreInterned() {
    assertThat(CipherSuite.forJavaName("TestCipherSuite")).isSameAs(CipherSuite.forJavaName("TestCipherSuite"));
    assertThat(CipherSuite.forJavaName(CipherSuite.TLS_KRB5_WITH_DES_CBC_MD5.javaName()))
        .isSameAs(CipherSuite.TLS_KRB5_WITH_DES_CBC_MD5);
  }

  /**
   * Tests that interned CipherSuite instances remain the case across garbage collections, even if
   * the String used to construct them is no longer strongly referenced outside of the CipherSuite.
   */
  @SuppressWarnings("RedundantStringConstructorCall")
  @Test public void instancesAreInterned_survivesGarbageCollection() {
    // We're not holding onto a reference to this String instance outside of the CipherSuite...
    CipherSuite cs = CipherSuite.forJavaName(new String("FakeCipherSuite_instancesAreInterned"));
    System.gc(); // Unless cs references the String instance, it may now be garbage collected.
    assertThat(CipherSuite.forJavaName(new String(cs.javaName()))).isSameAs(cs);
  }

  @Test public void equals() {
    assertThat(CipherSuite.forJavaName("cipher")).isEqualTo(CipherSuite.forJavaName("cipher"));
    assertThat(CipherSuite.forJavaName("cipherB")).isNotEqualTo(CipherSuite.forJavaName("cipherA"));
    assertThat(CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5).isEqualTo(
        CipherSuite.forJavaName("SSL_RSA_EXPORT_WITH_RC4_40_MD5"));
    assertThat(CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256).isNotEqualTo(
        CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5);
  }

  @Test public void forJavaName_acceptsArbitraryStrings() {
    // Shouldn't throw.
    CipherSuite.forJavaName("example CipherSuite name that is not in the whitelist");
  }

  @Test public void javaName_examples() {
    assertThat(CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5.javaName()).isEqualTo(
        "SSL_RSA_EXPORT_WITH_RC4_40_MD5");
    assertThat(CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256.javaName()).isEqualTo(
        "TLS_RSA_WITH_AES_128_CBC_SHA256");
    assertThat(CipherSuite.forJavaName("TestCipherSuite").javaName()).isEqualTo("TestCipherSuite");
  }

  @Test public void javaName_equalsToString() {
    assertThat(CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5.toString()).isEqualTo(
        CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5.javaName);
    assertThat(CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256.toString()).isEqualTo(
        CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256.javaName);
  }

  /**
   * On the Oracle JVM some older cipher suites have the "SSL_" prefix and others have the "TLS_"
   * prefix. On the IBM JVM all cipher suites have the "SSL_" prefix.
   *
   * <p>Prior to OkHttp 3.3.1 we accepted either form and consider them equivalent. And since OkHttp
   * 3.7.0 this is also true. But OkHttp 3.3.1 through 3.6.0 treated these as different.
   */
  @Test public void forJavaName_fromLegacyEnumName() {
    // These would have been considered equal in OkHttp 3.3.1, but now aren't.
    assertThat(CipherSuite.forJavaName("SSL_RSA_EXPORT_WITH_RC4_40_MD5")).isEqualTo(
        CipherSuite.forJavaName("TLS_RSA_EXPORT_WITH_RC4_40_MD5"));
    assertThat(CipherSuite.forJavaName("SSL_DH_RSA_EXPORT_WITH_DES40_CBC_SHA")).isEqualTo(
        CipherSuite.forJavaName("TLS_DH_RSA_EXPORT_WITH_DES40_CBC_SHA"));
    assertThat(CipherSuite.forJavaName("SSL_FAKE_NEW_CIPHER")).isEqualTo(
        CipherSuite.forJavaName("TLS_FAKE_NEW_CIPHER"));
  }

  @Test public void applyIntersectionRetainsSslPrefixes() throws Exception {
    FakeSslSocket socket = new FakeSslSocket();
    socket.setEnabledProtocols(new String[] { "TLSv1" });
    socket.setSupportedCipherSuites(new String[] { "SSL_A", "SSL_B", "SSL_C", "SSL_D", "SSL_E" });
    socket.setEnabledCipherSuites(new String[] { "SSL_A", "SSL_B", "SSL_C" });

    ConnectionSpec connectionSpec = new ConnectionSpec.Builder(true)
        .tlsVersions(TlsVersion.TLS_1_0)
        .cipherSuites("TLS_A", "TLS_C", "TLS_E")
        .build();
    connectionSpec.apply(socket, false);

    assertArrayEquals(new String[] { "SSL_A", "SSL_C" }, socket.enabledCipherSuites);
  }

  @Test public void applyIntersectionRetainsTlsPrefixes() throws Exception {
    FakeSslSocket socket = new FakeSslSocket();
    socket.setEnabledProtocols(new String[] { "TLSv1" });
    socket.setSupportedCipherSuites(new String[] { "TLS_A", "TLS_B", "TLS_C", "TLS_D", "TLS_E" });
    socket.setEnabledCipherSuites(new String[] { "TLS_A", "TLS_B", "TLS_C" });

    ConnectionSpec connectionSpec = new ConnectionSpec.Builder(true)
        .tlsVersions(TlsVersion.TLS_1_0)
        .cipherSuites("SSL_A", "SSL_C", "SSL_E")
        .build();
    connectionSpec.apply(socket, false);

    assertArrayEquals(new String[] { "TLS_A", "TLS_C" }, socket.enabledCipherSuites);
  }

  @Test public void applyIntersectionAddsSslScsvForFallback() throws Exception {
    FakeSslSocket socket = new FakeSslSocket();
    socket.setEnabledProtocols(new String[] { "TLSv1" });
    socket.setSupportedCipherSuites(new String[] { "SSL_A", "SSL_FALLBACK_SCSV" });
    socket.setEnabledCipherSuites(new String[] { "SSL_A" });

    ConnectionSpec connectionSpec = new ConnectionSpec.Builder(true)
        .tlsVersions(TlsVersion.TLS_1_0)
        .cipherSuites("SSL_A")
        .build();
    connectionSpec.apply(socket, true);

    assertArrayEquals(new String[] { "SSL_A", "SSL_FALLBACK_SCSV" }, socket.enabledCipherSuites);
  }

  @Test public void applyIntersectionAddsTlsScsvForFallback() throws Exception {
    FakeSslSocket socket = new FakeSslSocket();
    socket.setEnabledProtocols(new String[] { "TLSv1" });
    socket.setSupportedCipherSuites(new String[] { "TLS_A", "TLS_FALLBACK_SCSV" });
    socket.setEnabledCipherSuites(new String[] { "TLS_A" });

    ConnectionSpec connectionSpec = new ConnectionSpec.Builder(true)
        .tlsVersions(TlsVersion.TLS_1_0)
        .cipherSuites("TLS_A")
        .build();
    connectionSpec.apply(socket, true);

    assertArrayEquals(new String[] { "TLS_A", "TLS_FALLBACK_SCSV" }, socket.enabledCipherSuites);
  }

  @Test public void applyIntersectionToProtocolVersion() throws Exception {
    FakeSslSocket socket = new FakeSslSocket();
    socket.setEnabledProtocols(new String[] { "TLSv1", "TLSv1.1", "TLSv1.2" });
    socket.setSupportedCipherSuites(new String[] { "TLS_A" });
    socket.setEnabledCipherSuites(new String[] { "TLS_A" });

    ConnectionSpec connectionSpec = new ConnectionSpec.Builder(true)
        .tlsVersions(TlsVersion.TLS_1_1, TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
        .cipherSuites("TLS_A")
        .build();
    connectionSpec.apply(socket, false);

    assertArrayEquals(new String[] { "TLSv1.1", "TLSv1.2" }, socket.enabledProtocols);
  }

  static final class FakeSslSocket extends DelegatingSSLSocket {
    private String[] enabledProtocols;
    private String[] supportedCipherSuites;
    private String[] enabledCipherSuites;

    FakeSslSocket() {
      super(null);
    }

    @Override public String[] getEnabledProtocols() {
      return enabledProtocols;
    }

    @Override public void setEnabledProtocols(String[] enabledProtocols) {
      this.enabledProtocols = enabledProtocols;
    }

    @Override public String[] getSupportedCipherSuites() {
      return supportedCipherSuites;
    }

    public void setSupportedCipherSuites(String[] supportedCipherSuites) {
      this.supportedCipherSuites = supportedCipherSuites;
    }

    @Override public String[] getEnabledCipherSuites() {
      return enabledCipherSuites;
    }

    @Override public void setEnabledCipherSuites(String[] enabledCipherSuites) {
      this.enabledCipherSuites = enabledCipherSuites;
    }
  }
}
