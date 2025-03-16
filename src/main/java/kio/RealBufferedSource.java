package kio;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HexFormat;

public final class RealBufferedSource implements BufferedSource {
  public final Buffer buffer = new Buffer();
  public final Source source;
  boolean closed;

  public RealBufferedSource(Source source) {
    if (source == null) throw new NullPointerException("source == null");
    this.source = source;
  }

  @Override public Buffer buffer() {
    return buffer;
  }

  @Override public int read(Buffer sink, int byteCount) throws IOException {
    if (sink == null) throw new IllegalArgumentException("sink == null");
    if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
    if (closed) throw new IllegalStateException("closed");

    if (buffer.size == 0) {
      int read = source.read(buffer, Segment.SIZE);
      if (read == -1) return -1;
    }

    int toRead = Math.min(byteCount, buffer.size);
    return buffer.read(sink, toRead);
  }

  @Override public boolean exhausted() throws IOException {
    if (closed) throw new IllegalStateException("closed");
    return buffer.exhausted() && source.read(buffer, Segment.SIZE) == -1;
  }

  @Override public void require(int byteCount) throws IOException {
    if (!request(byteCount)) throw new EOFException();
  }

  @Override public boolean request(int byteCount) throws IOException {
    if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
    if (closed) throw new IllegalStateException("closed");
    while (buffer.size < byteCount) {
      if (source.read(buffer, Segment.SIZE) == -1) return false;
    }
    return true;
  }

  @Override public byte readByte() throws IOException {
    require(1);
    return buffer.readByte();
  }

  @Override public byte[] readByteArray() throws IOException {
    buffer.writeAll(source);
    return buffer.readByteArray();
  }

  @Override public void readFully(byte[] sink) throws IOException {
    try {
      require(sink.length);
    } catch (EOFException e) {
      // The underlying source is exhausted. Copy the bytes we got before rethrowing.
      int offset = 0;
      while (buffer.size > 0) {
        int read = buffer.read(sink, offset, buffer.size);
        if (read == -1) throw new AssertionError();
        offset += read;
      }
      throw e;
    }
    buffer.readFully(sink);
  }

  @Override public String readUtf8() throws IOException {
    buffer.writeAll(source);
    return buffer.readUtf8();
  }

  @Override public String readUtf8(int byteCount) throws IOException {
    require(byteCount);
    return buffer.readUtf8(byteCount);
  }

  @Override public String readString(Charset charset) throws IOException {
    if (charset == null) throw new IllegalArgumentException("charset == null");

    buffer.writeAll(source);
    return buffer.readString(charset);
  }

  @Override public String readString(int byteCount, Charset charset) throws IOException {
    require(byteCount);
    if (charset == null) throw new IllegalArgumentException("charset == null");
    return buffer.readString(byteCount, charset);
  }

  @Override public String readUtf8LineStrict() throws IOException {
    return readUtf8LineStrict(Integer.MAX_VALUE);
  }

  @Override public String readUtf8LineStrict(int limit) throws IOException {
    if (limit < 0) throw new IllegalArgumentException("limit < 0: " + limit);
    int scanLength = limit == Integer.MAX_VALUE ? Integer.MAX_VALUE : limit + 1;
    int newline = indexOf((byte) '\n', 0, scanLength);
    if (newline != -1) return buffer.readUtf8Line(newline);
    if (scanLength < Integer.MAX_VALUE
        && request(scanLength) && buffer.getByte(scanLength - 1) == '\r'
        && request(scanLength + 1) && buffer.getByte(scanLength) == '\n') {
      return buffer.readUtf8Line(scanLength); // The line was 'limit' UTF-8 bytes followed by \r\n.
    }
    Buffer data = new Buffer();
    buffer.copyTo(data, 0, Math.min(32, buffer.size()));
    throw new EOFException("\\n not found: limit=" + Math.min(buffer.size(), limit)
        + " content=" + HexFormat.of().formatHex(data.readByteArray()) + '…');
  }

  @Override public short readShort() throws IOException {
    require(2);
    return buffer.readShort();
  }

  @Override public short readShortLe() throws IOException {
    require(2);
    return buffer.readShortLe();
  }

  @Override public int readInt() throws IOException {
    require(4);
    return buffer.readInt();
  }

  @Override public int readIntLe() throws IOException {
    require(4);
    return buffer.readIntLe();
  }

  @Override public int readHexadecimalInt() throws IOException {
    require(1);

    for (int pos = 0; request(pos + 1); pos++) {
      byte b = buffer.getByte(pos);
      if ((b < '0' || b > '9') && (b < 'a' || b > 'f') && (b < 'A' || b > 'F')) {
        // Non-digit, or non-leading negative sign.
        if (pos == 0) {
          throw new NumberFormatException(String.format(
              "Expected leading [0-9a-fA-F] character but was %#x", b));
        }
        break;
      }
    }

    return buffer.readHexadecimalInt();
  }

  @Override public void skip(int byteCount) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    while (byteCount > 0) {
      if (buffer.size == 0 && source.read(buffer, Segment.SIZE) == -1) {
        throw new EOFException();
      }
      int toSkip = Math.min(byteCount, buffer.size());
      buffer.skip(toSkip);
      byteCount -= toSkip;
    }
  }

  @Override public int indexOf(byte b) throws IOException {
    return indexOf(b, 0, Integer.MAX_VALUE);
  }

  @Override public int indexOf(byte b, int fromIndex, int toIndex) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    if (fromIndex < 0 || toIndex < fromIndex) {
      throw new IllegalArgumentException(
          String.format("fromIndex=%s toIndex=%s", fromIndex, toIndex));
    }

    while (fromIndex < toIndex) {
      int result = buffer.indexOf(b, fromIndex, toIndex);
      if (result != -1) return result;

      // The byte wasn't in the buffer. Give up if we've already reached our target size or if the
      // underlying stream is exhausted.
      int lastBufferSize = buffer.size;
      if (lastBufferSize >= toIndex || source.read(buffer, Segment.SIZE) == -1) return -1;

      // Continue the search from where we left off.
      fromIndex = Math.max(fromIndex, lastBufferSize);
    }
    return -1;
  }

  @Override public InputStream inputStream() {
    return new InputStream() {
      @Override public int read() throws IOException {
        if (closed) throw new IOException("closed");
        if (buffer.size == 0) {
          int count = source.read(buffer, Segment.SIZE);
          if (count == -1) return -1;
        }
        return buffer.readByte() & 0xff;
      }

      @Override public int read(byte[] data, int offset, int byteCount) throws IOException {
        if (closed) throw new IOException("closed");
        Util.checkOffsetAndCount(data.length, offset, byteCount);

        if (buffer.size == 0) {
          int count = source.read(buffer, Segment.SIZE);
          if (count == -1) return -1;
        }

        return buffer.read(data, offset, byteCount);
      }

      @Override public int available() throws IOException {
        if (closed) throw new IOException("closed");
        return buffer.size;
      }

      @Override public void close() throws IOException {
        RealBufferedSource.this.close();
      }

      @Override public String toString() {
        return RealBufferedSource.this + ".inputStream()";
      }
    };
  }

  @Override public void close() throws IOException {
    if (closed) return;
    closed = true;
    source.close();
    buffer.clear();
  }

  @Override public Timeout timeout() {
    return source.timeout();
  }

  @Override public String toString() {
    return "buffer(" + source + ")";
  }
}
