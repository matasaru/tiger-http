package kio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * A source that keeps a buffer internally so that callers can do small reads without a performance
 * penalty. It also allows clients to read ahead, buffering as much as necessary before consuming
 * input.
 */
public interface BufferedSource extends Source  {
  /**
   * Returns this source's internal buffer.
   */
  Buffer buffer();

  /**
   * Returns true if there are no more bytes in this source. This will block until there are bytes
   * to read or the source is definitely exhausted.
   */
  boolean exhausted() throws IOException;

  /**
   * Returns when the buffer contains at least {@code byteCount} bytes. Throws an
   * {@link java.io.EOFException} if the source is exhausted before the required bytes can be read.
   */
  void require(long byteCount) throws IOException;

  /**
   * Returns true when the buffer contains at least {@code byteCount} bytes, expanding it as
   * necessary. Returns false if the source is exhausted before the requested bytes can be read.
   */
  boolean request(long byteCount) throws IOException;

  /** Removes a byte from this source and returns it. */
  byte readByte() throws IOException;

  /**
   * Removes two bytes from this source and returns a big-endian short. <pre>{@code
   *
   *   Buffer buffer = new Buffer()
   *       .writeByte(0x7f)
   *       .writeByte(0xff)
   *       .writeByte(0x00)
   *       .writeByte(0x0f);
   *   assertEquals(4, buffer.size());
   *
   *   assertEquals(32767, buffer.readShort());
   *   assertEquals(2, buffer.size());
   *
   *   assertEquals(15, buffer.readShort());
   *   assertEquals(0, buffer.size());
   * }</pre>
   */
  short readShort() throws IOException;

  /**
   * Removes two bytes from this source and returns a little-endian short. <pre>{@code
   *
   *   Buffer buffer = new Buffer()
   *       .writeByte(0xff)
   *       .writeByte(0x7f)
   *       .writeByte(0x0f)
   *       .writeByte(0x00);
   *   assertEquals(4, buffer.size());
   *
   *   assertEquals(32767, buffer.readShortLe());
   *   assertEquals(2, buffer.size());
   *
   *   assertEquals(15, buffer.readShortLe());
   *   assertEquals(0, buffer.size());
   * }</pre>
   */
  short readShortLe() throws IOException;

  /**
   * Removes four bytes from this source and returns a big-endian int. <pre>{@code
   *
   *   Buffer buffer = new Buffer()
   *       .writeByte(0x7f)
   *       .writeByte(0xff)
   *       .writeByte(0xff)
   *       .writeByte(0xff)
   *       .writeByte(0x00)
   *       .writeByte(0x00)
   *       .writeByte(0x00)
   *       .writeByte(0x0f);
   *   assertEquals(8, buffer.size());
   *
   *   assertEquals(2147483647, buffer.readInt());
   *   assertEquals(4, buffer.size());
   *
   *   assertEquals(15, buffer.readInt());
   *   assertEquals(0, buffer.size());
   * }</pre>
   */
  int readInt() throws IOException;

  /**
   * Removes four bytes from this source and returns a little-endian int. <pre>{@code
   *
   *   Buffer buffer = new Buffer()
   *       .writeByte(0xff)
   *       .writeByte(0xff)
   *       .writeByte(0xff)
   *       .writeByte(0x7f)
   *       .writeByte(0x0f)
   *       .writeByte(0x00)
   *       .writeByte(0x00)
   *       .writeByte(0x00);
   *   assertEquals(8, buffer.size());
   *
   *   assertEquals(2147483647, buffer.readIntLe());
   *   assertEquals(4, buffer.size());
   *
   *   assertEquals(15, buffer.readIntLe());
   *   assertEquals(0, buffer.size());
   * }</pre>
   */
  int readIntLe() throws IOException;

  /**
   * Reads a long form this source in hexadecimal form (i.e., as a string in base 16). This will
   * iterate until a non-hexadecimal character is found. <pre>{@code
   *
   *   Buffer buffer = new Buffer()
   *       .writeUtf8("ffff CAFEBABE 10");
   *
   *   assertEquals(65535L, buffer.readHexadecimalUnsignedLong());
   *   assertEquals(' ', buffer.readByte());
   *   assertEquals(0xcafebabeL, buffer.readHexadecimalUnsignedLong());
   *   assertEquals(' ', buffer.readByte());
   *   assertEquals(0x10L, buffer.readHexadecimalUnsignedLong());
   * }</pre>
   *
   * @throws NumberFormatException if the found hexadecimal does not fit into a {@code long} or
   * hexadecimal was not found.
   */
  long readHexadecimalUnsignedLong() throws IOException;

  /**
   * Reads and discards {@code byteCount} bytes from this source. Throws an
   * {@link java.io.EOFException} if the source is exhausted before the
   * requested bytes can be skipped.
   */
  void skip(long byteCount) throws IOException;

  /** Removes all bytes from this and returns them as a byte array. */
  byte[] readByteArray() throws IOException;

  /**
   * Removes exactly {@code sink.length} bytes from this and copies them into {@code sink}. Throws
   * an {@link java.io.EOFException} if the requested number of bytes cannot be read.
   */
  void readFully(byte[] sink) throws IOException;

  /**
   * Removes all bytes from this, decodes them as UTF-8, and returns the string. Returns the empty
   * string if this source is empty. <pre>{@code
   *
   *   Buffer buffer = new Buffer()
   *       .writeUtf8("Uh uh uh!")
   *       .writeByte(' ')
   *       .writeUtf8("You didn't say the magic word!");
   *
   *   assertEquals("Uh uh uh! You didn't say the magic word!", buffer.readUtf8());
   *   assertEquals(0, buffer.size());
   *
   *   assertEquals("", buffer.readUtf8());
   *   assertEquals(0, buffer.size());
   * }</pre>
   */
  String readUtf8() throws IOException;

  /**
   * Removes {@code byteCount} bytes from this, decodes them as UTF-8, and returns the string.
   * <pre>{@code
   *
   *   Buffer buffer = new Buffer()
   *       .writeUtf8("Uh uh uh!")
   *       .writeByte(' ')
   *       .writeUtf8("You didn't say the magic word!");
   *   assertEquals(40, buffer.size());
   *
   *   assertEquals("Uh uh uh! You ", buffer.readUtf8(14));
   *   assertEquals(26, buffer.size());
   *
   *   assertEquals("didn't say the", buffer.readUtf8(14));
   *   assertEquals(12, buffer.size());
   *
   *   assertEquals(" magic word!", buffer.readUtf8(12));
   *   assertEquals(0, buffer.size());
   * }</pre>
   */
  String readUtf8(long byteCount) throws IOException;

  /**
   * Removes and returns characters up to but not including the next line break. A line break is
   * either {@code "\n"} or {@code "\r\n"}; these characters are not included in the result.
   *
   * <p><strong>On the end of the stream this method throws.</strong> Every call must consume either
   * '\r\n' or '\n'. If these characters are absent in the stream, an {@link java.io.EOFException}
   * is thrown. Use this for machine-generated data where a missing line break implies truncated
   * input.
   */
  String readUtf8LineStrict() throws IOException;

  /**
   * Like {@link #readUtf8LineStrict()}, except this allows the caller to specify the longest
   * allowed match. Use this to protect against streams that may not include
   * {@code "\n"} or {@code "\r\n"}.
   *
   * <p>The returned string will have at most {@code limit} UTF-8 bytes, and the maximum number
   * of bytes scanned is {@code limit + 2}. If {@code limit == 0} this will always throw
   * an {@code EOFException} because no bytes will be scanned.
   *
   * <p>This method is safe. No bytes are discarded if the match fails, and the caller is free
   * to try another match: <pre>{@code
   *
   *   Buffer buffer = new Buffer();
   *   buffer.writeUtf8("12345\r\n");
   *
   *   // This will throw! There must be \r\n or \n at the limit or before it.
   *   buffer.readUtf8LineStrict(4);
   *
   *   // No bytes have been consumed so the caller can retry.
   *   assertEquals("12345", buffer.readUtf8LineStrict(5));
   * }</pre>
   */
  String readUtf8LineStrict(long limit) throws IOException;

  /** Removes all bytes from this, decodes them as {@code charset}, and returns the string. */
  String readString(Charset charset) throws IOException;

  /**
   * Removes {@code byteCount} bytes from this, decodes them as {@code charset}, and returns the
   * string.
   */
  String readString(long byteCount, Charset charset) throws IOException;

  /** Equivalent to {@link #indexOf(byte, long) indexOf(b, 0)}. */
  long indexOf(byte b) throws IOException;

  /**
   * Returns the index of {@code b} if it is found in the range of {@code fromIndex} inclusive
   * to {@code toIndex} exclusive. If {@code b} isn't found, or if {@code fromIndex == toIndex},
   * then -1 is returned.
   *
   * <p>The scan terminates at either {@code toIndex} or the end of the buffer, whichever comes
   * first. The maximum number of bytes scanned is {@code toIndex-fromIndex}.
   */
  long indexOf(byte b, long fromIndex, long toIndex) throws IOException;

  /** Equivalent to {@link #indexOfElement(ByteString, long) indexOfElement(targetBytes, 0)}. */
  long indexOfElement(ByteString targetBytes) throws IOException;

  /**
   * Returns the first index in this buffer that is at or after {@code fromIndex} and that contains
   * any of the bytes in {@code targetBytes}. This expands the buffer as necessary until a target
   * byte is found. This reads an unbounded number of bytes into the buffer. Returns -1 if the
   * stream is exhausted before the requested byte is found. <pre>{@code
   *
   *   ByteString ANY_VOWEL = ByteString.encodeUtf8("AEOIUaeoiu");
   *
   *   Buffer buffer = new Buffer();
   *   buffer.writeUtf8("Dr. Alan Grant");
   *
   *   assertEquals(4,  buffer.indexOfElement(ANY_VOWEL));    // 'A' in 'Alan'.
   *   assertEquals(11, buffer.indexOfElement(ANY_VOWEL, 9)); // 'a' in 'Grant'.
   * }</pre>
   */
  long indexOfElement(ByteString targetBytes, long fromIndex) throws IOException;

  /** Returns an input stream that reads from this source. */
  InputStream inputStream();
}
