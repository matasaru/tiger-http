package kio;

import java.io.IOException;
import java.io.OutputStream;

/**
 * A sink that keeps a buffer internally so that callers can do small writes
 * without a performance penalty.
 */
public interface BufferedSink extends Sink {
  /** Returns this sink's internal buffer. */
  Buffer buffer();

  /**
   * Like {@link OutputStream#write(byte[])}, this writes a complete byte array to
   * this sink.
   */
  BufferedSink write(byte[] source) throws IOException;

  /**
   * Like {@link OutputStream#write(byte[], int, int)}, this writes {@code byteCount}
   * bytes of {@code source}, starting at {@code offset}.
   */
  BufferedSink write(byte[] source, int offset, int byteCount) throws IOException;

  /**
   * Removes all bytes from {@code source} and appends them to this sink. Returns the
   * number of bytes read which will be 0 if {@code source} is exhausted.
   */
  void writeAll(Source source) throws IOException;

  /** Removes {@code byteCount} bytes from {@code source} and appends them to this sink. */
  BufferedSink write(Source source, long byteCount) throws IOException;

  /**
   * Encodes {@code string} in UTF-8 and writes it to this sink. <pre>{@code
   *
   *   Buffer buffer = new Buffer();
   *   buffer.writeUtf8("Uh uh uh!");
   *   buffer.writeByte(' ');
   *   buffer.writeUtf8("You didn't say the magic word!");
   *
   *   assertEquals("Uh uh uh! You didn't say the magic word!", buffer.readUtf8());
   * }</pre>
   */
  BufferedSink writeUtf8(String string) throws IOException;

  /**
   * Encodes the characters at {@code beginIndex} up to {@code endIndex} from {@code string} in
   * UTF-8 and writes it to this sink. <pre>{@code
   *
   *   Buffer buffer = new Buffer();
   *   buffer.writeUtf8("I'm a hacker!\n", 6, 12);
   *   buffer.writeByte(' ');
   *   buffer.writeUtf8("That's what I said: you're a nerd.\n", 29, 33);
   *   buffer.writeByte(' ');
   *   buffer.writeUtf8("I prefer to be called a hacker!\n", 24, 31);
   *
   *   assertEquals("hacker nerd hacker!", buffer.readUtf8());
   * }</pre>
   */
  BufferedSink writeUtf8(String string, int beginIndex, int endIndex) throws IOException;

  /** Encodes {@code codePoint} in UTF-8 and writes it to this sink. */
  void writeUtf8CodePoint(int codePoint) throws IOException;

  /** Writes a byte to this sink. */
  BufferedSink writeByte(int b) throws IOException;

  /**
   * Writes a long to this sink in hexadecimal form (i.e., as a string in base 16). <pre>{@code
   *
   *   Buffer buffer = new Buffer();
   *   buffer.writeHexadecimalUnsignedLong(65535L);
   *   buffer.writeByte(' ');
   *   buffer.writeHexadecimalUnsignedLong(0xcafebabeL);
   *   buffer.writeByte(' ');
   *   buffer.writeHexadecimalUnsignedLong(0x10L);
   *
   *   assertEquals("ffff cafebabe 10", buffer.readUtf8());
   * }</pre>
   */
  BufferedSink writeHexadecimalUnsignedLong(long v) throws IOException;

  /**
   * Writes all buffered data to the underlying sink, if one exists. Then that sink is recursively
   * flushed which pushes data as far as possible towards its ultimate destination. Typically that
   * destination is a network socket or file. <pre>{@code
   *
   *   BufferedSink b0 = new Buffer();
   *   BufferedSink b1 = Okio.buffer(b0);
   *   BufferedSink b2 = Okio.buffer(b1);
   *
   *   b2.writeUtf8("hello");
   *   assertEquals(5, b2.buffer().size());
   *   assertEquals(0, b1.buffer().size());
   *   assertEquals(0, b0.buffer().size());
   *
   *   b2.flush();
   *   assertEquals(0, b2.buffer().size());
   *   assertEquals(0, b1.buffer().size());
   *   assertEquals(5, b0.buffer().size());
   * }</pre>
   */
  @Override void flush() throws IOException;

  /**
   * Writes all buffered data to the underlying sink, if one exists. Like {@link #flush}, but
   * weaker. Call this before this buffered sink goes out of scope so that its data can reach its
   * destination. <pre>{@code
   *
   *   BufferedSink b0 = new Buffer();
   *   BufferedSink b1 = Okio.buffer(b0);
   *   BufferedSink b2 = Okio.buffer(b1);
   *
   *   b2.writeUtf8("hello");
   *   assertEquals(5, b2.buffer().size());
   *   assertEquals(0, b1.buffer().size());
   *   assertEquals(0, b0.buffer().size());
   *
   *   b2.emit();
   *   assertEquals(0, b2.buffer().size());
   *   assertEquals(5, b1.buffer().size());
   *   assertEquals(0, b0.buffer().size());
   *
   *   b1.emit();
   *   assertEquals(0, b2.buffer().size());
   *   assertEquals(0, b1.buffer().size());
   *   assertEquals(5, b0.buffer().size());
   * }</pre>
   */
  void emit() throws IOException;

  /**
   * Writes complete segments to the underlying sink, if one exists. Like {@link #flush}, but
   * weaker. Use this to limit the memory held in the buffer to a single segment. Typically
   * application code will not need to call this: it is only necessary when application code writes
   * directly to this {@linkplain #buffer() sink's buffer}. <pre>{@code
   *
   *   BufferedSink b0 = new Buffer();
   *   BufferedSink b1 = Okio.buffer(b0);
   *   BufferedSink b2 = Okio.buffer(b1);
   *
   *   b2.buffer().write(new byte[20_000]);
   *   assertEquals(20_000, b2.buffer().size());
   *   assertEquals(     0, b1.buffer().size());
   *   assertEquals(     0, b0.buffer().size());
   *
   *   b2.emitCompleteSegments();
   *   assertEquals( 3_616, b2.buffer().size());
   *   assertEquals(     0, b1.buffer().size());
   *   assertEquals(16_384, b0.buffer().size()); // This example assumes 8192 byte segments.
   * }</pre>
   */
  BufferedSink emitCompleteSegments() throws IOException;
}
