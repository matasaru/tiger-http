package kio;

import java.io.EOFException;
import java.io.IOException;

public final class RealBufferedSink implements BufferedSink {
  public final Buffer buffer = new Buffer();
  public final Sink sink;
  boolean closed;

  public RealBufferedSink(Sink sink) {
    if (sink == null) throw new NullPointerException("sink == null");
    this.sink = sink;
  }

  @Override public Buffer buffer() {
    return buffer;
  }

  @Override public void write(Buffer source, long byteCount)
      throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.write(source, byteCount);
    emitCompleteSegments();
  }

  @Override public BufferedSink write(ByteString byteString) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.write(byteString);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeUtf8(String string) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeUtf8(string);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeUtf8(String string, int beginIndex, int endIndex)
      throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeUtf8(string, beginIndex, endIndex);
    return emitCompleteSegments();
  }

  @Override public void writeUtf8CodePoint(int codePoint) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeUtf8CodePoint(codePoint);
    emitCompleteSegments();
  }

  @Override public BufferedSink write(byte[] source) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.write(source);
    return emitCompleteSegments();
  }

  @Override public BufferedSink write(byte[] source, int offset, int byteCount) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.write(source, offset, byteCount);
    return emitCompleteSegments();
  }

  @Override public void writeAll(Source source) throws IOException {
    if (source == null) throw new IllegalArgumentException("source == null");
    while (source.read(buffer, Segment.SIZE) != -1) {
      emitCompleteSegments();
    }
  }

  @Override public BufferedSink write(Source source, long byteCount) throws IOException {
    while (byteCount > 0) {
      long read = source.read(buffer, byteCount);
      if (read == -1) throw new EOFException();
      byteCount -= read;
      emitCompleteSegments();
    }
    return this;
  }

  @Override public BufferedSink writeByte(int b) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeByte(b);
    return emitCompleteSegments();
  }

  @Override public void writeInt(int i) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeInt(i);
    emitCompleteSegments();
  }

  @Override public BufferedSink writeDecimalLong(long v) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeDecimalLong(v);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeHexadecimalUnsignedLong(long v) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeHexadecimalUnsignedLong(v);
    return emitCompleteSegments();
  }

  @Override public BufferedSink emitCompleteSegments() throws IOException {
    if (closed) throw new IllegalStateException("closed");
    long byteCount = buffer.completeSegmentByteCount();
    if (byteCount > 0) sink.write(buffer, byteCount);
    return this;
  }

  @Override public void emit() throws IOException {
    if (closed) throw new IllegalStateException("closed");
    long byteCount = buffer.size();
    if (byteCount > 0) sink.write(buffer, byteCount);
  }

  @Override public void flush() throws IOException {
    if (closed) throw new IllegalStateException("closed");
    if (buffer.size > 0) {
      sink.write(buffer, buffer.size);
    }
    sink.flush();
  }

  @Override public void close() throws IOException {
    if (closed) return;

    // Emit buffered data to the underlying sink. If this fails, we still need
    // to close the sink; otherwise we risk leaking resources.
    Throwable thrown = null;
    try {
      if (buffer.size > 0) {
        sink.write(buffer, buffer.size);
      }
    } catch (Throwable e) {
      thrown = e;
    }

    try {
      sink.close();
    } catch (Throwable e) {
      if (thrown == null) thrown = e;
    }
    closed = true;

    if (thrown != null) Util.sneakyRethrow(thrown);
  }

  @Override public Timeout timeout() {
    return sink.timeout();
  }

  @Override public String toString() {
    return "buffer(" + sink + ")";
  }
}
