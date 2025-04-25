package kio;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * A collection of bytes in memory.
 *
 * <p><strong>Moving data from one buffer to another is fast.</strong> Instead
 * of copying bytes from one place in memory to another, this class just changes
 * ownership of the underlying byte arrays.
 *
 * <p><strong>This buffer grows with your data.</strong> Just like ArrayList,
 * each buffer starts small. It consumes only the memory it needs to.
 *
 * <p><strong>This buffer pools its byte arrays.</strong> When you allocate a
 * byte array in Java, the runtime must zero-fill the requested array before
 * returning it to you. Even if you're going to write over that space anyway.
 * This class avoids zero-fill and GC churn by pooling byte arrays.
 */
public final class Buffer implements BufferedSource, BufferedSink, Cloneable {
  private static final byte[] DIGITS =
      { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

  Segment head;
  int size;

  public Buffer() {
  }

  /** Returns the number of bytes currently in this buffer. */
  public int size() {
    return size;
  }

  @Override public Buffer buffer() {
    return this;
  }

  @Override public Buffer emitCompleteSegments() {
    return this; // Nowhere to emit to!
  }

  @Override public void emit() {
  }

  @Override public boolean exhausted() {
    return size == 0;
  }

  @Override public void require(int byteCount) throws EOFException {
    if (size < byteCount) throw new EOFException();
  }

  @Override public boolean request(int byteCount) {
    return size >= byteCount;
  }

  @Override public InputStream inputStream() {
    return new InputStream() {
      @Override public int read() {
        if (size > 0) return readByte() & 0xff;
        return -1;
      }

      @Override public int read(byte[] sink, int offset, int byteCount) {
        return Buffer.this.read(sink, offset, byteCount);
      }

      @Override public int available() {
        return size;
      }

      @Override public void close() {
      }

      @Override public String toString() {
        return Buffer.this + ".inputStream()";
      }
    };
  }

  /** Copy {@code byteCount} bytes from this, starting at {@code offset}, to {@code out}. */
  public void copyTo(Buffer out, int offset, int byteCount) {
    if (out == null) throw new IllegalArgumentException("out == null");
    Util.checkOffsetAndCount(size, offset, byteCount);
    if (byteCount == 0) return;

    out.size += byteCount;

    // Skip segments that we aren't copying from.
    Segment s = head;
    for (; offset >= (s.limit - s.pos); s = s.next) {
      offset -= (s.limit - s.pos);
    }

    // Copy one segment at a time.
    for (; byteCount > 0; s = s.next) {
      Segment copy = s.sharedCopy();
      copy.pos += offset;
      copy.limit = Math.min(copy.pos + byteCount, copy.limit);
      if (out.head == null) {
        out.head = copy.next = copy.prev = copy;
      } else {
        out.head.prev.push(copy);
      }
      byteCount -= copy.limit - copy.pos;
      offset = 0;
    }
  }

  /**
   * Returns the number of bytes in segments that are not writable. This is the
   * number of bytes that can be flushed immediately to an underlying sink
   * without harming throughput.
   */
  public int completeSegmentByteCount() {
    int result = size;
    if (result == 0) return 0;

    // Omit the tail if it's still writable.
    Segment tail = head.prev;
    if (tail.limit < Segment.SIZE && tail.owner) {
      result -= tail.limit - tail.pos;
    }

    return result;
  }

  @Override public byte readByte() {
    if (size == 0) throw new IllegalStateException("size == 0");

    Segment segment = head;
    int pos = segment.pos;
    int limit = segment.limit;

    byte[] data = segment.data;
    byte b = data[pos++];
    size -= 1;

    if (pos == limit) {
      head = segment.pop();
      SegmentPool.recycle(segment);
    } else {
      segment.pos = pos;
    }

    return b;
  }

  /** Returns the byte at {@code pos}. */
  public byte getByte(int pos) {
    Util.checkOffsetAndCount(size, pos, 1);
    if (size - pos > pos) {
      for (Segment s = head; true; s = s.next) {
        int segmentByteCount = s.limit - s.pos;
        if (pos < segmentByteCount) return s.data[s.pos + pos];
        pos -= segmentByteCount;
      }
    } else {
      pos -= size;
      for (Segment s = head.prev; true; s = s.prev) {
        pos += s.limit - s.pos;
        if (pos >= 0) return s.data[s.pos + pos];
      }
    }
  }

  @Override public short readShort() {
    if (size < 2) throw new IllegalStateException("size < 2: " + size);

    Segment segment = head;
    int pos = segment.pos;
    int limit = segment.limit;

    // If the short is split across multiple segments, delegate to readByte().
    if (limit - pos < 2) {
      int s = (readByte() & 0xff) << 8
          |   (readByte() & 0xff);
      return (short) s;
    }

    byte[] data = segment.data;
    int s = (data[pos++] & 0xff) << 8
        |   (data[pos++] & 0xff);
    size -= 2;

    if (pos == limit) {
      head = segment.pop();
      SegmentPool.recycle(segment);
    } else {
      segment.pos = pos;
    }

    return (short) s;
  }

  @Override public int readInt() {
    if (size < 4) throw new IllegalStateException("size < 4: " + size);

    Segment segment = head;
    int pos = segment.pos;
    int limit = segment.limit;

    // If the int is split across multiple segments, delegate to readByte().
    if (limit - pos < 4) {
      return (readByte() & 0xff) << 24
          |  (readByte() & 0xff) << 16
          |  (readByte() & 0xff) <<  8
          |  (readByte() & 0xff);
    }

    byte[] data = segment.data;
    int i = (data[pos++] & 0xff) << 24
        |   (data[pos++] & 0xff) << 16
        |   (data[pos++] & 0xff) <<  8
        |   (data[pos++] & 0xff);
    size -= 4;

    if (pos == limit) {
      head = segment.pop();
      SegmentPool.recycle(segment);
    } else {
      segment.pos = pos;
    }

    return i;
  }

  @Override public short readShortLe() {
    int i = readShort() & 0xffff;
    int reversed = (i & 0xff00) >>> 8
        |          (i & 0x00ff)  << 8;
    return (short) reversed;
  }

  @Override public int readIntLe() {
    int i = readInt();
    return (i & 0xff000000) >>> 24
        |  (i & 0x00ff0000) >>>  8
        |  (i & 0x0000ff00)  <<  8
        |  (i & 0x000000ff)  << 24;
  }

  @Override public int readHexadecimalInt() {
    if (size == 0) throw new IllegalStateException("size == 0");

    int value = 0;
    int seen = 0;
    boolean done = false;

    do {
      Segment segment = head;

      byte[] data = segment.data;
      int pos = segment.pos;
      int limit = segment.limit;

      for (; pos < limit; pos++, seen++) {
        int digit;

        byte b = data[pos];
        if (b >= '0' && b <= '9') {
          digit = b - '0';
        } else if (b >= 'a' && b <= 'f') {
          digit = b - 'a' + 10;
        } else if (b >= 'A' && b <= 'F') {
          digit = b - 'A' + 10; // We never write uppercase, but we support reading it.
        } else {
          if (seen == 0) {
            throw new NumberFormatException(
                "Expected leading [0-9a-fA-F] character but was 0x" + Integer.toHexString(b));
          }
          // Set a flag to stop iteration. We still need to run through segment updating below.
          done = true;
          break;
        }

        // Detect when the shift will overflow.
        if ((value & 0xf0000000) != 0) {
          Buffer buffer = new Buffer().writeHexadecimalInt(value).writeByte(b);
          throw new NumberFormatException("Number too large: " + buffer.readUtf8());
        }

        value <<= 4;
        value |= digit;
      }

      if (pos == limit) {
        head = segment.pop();
        SegmentPool.recycle(segment);
      } else {
        segment.pos = pos;
      }
    } while (!done && head != null);

    size -= seen;
    return value;
  }

  @Override public String readUtf8() {
    try {
      return readString(size, StandardCharsets.UTF_8);
    } catch (EOFException e) {
      throw new AssertionError(e);
    }
  }

  @Override public String readUtf8(int byteCount) throws EOFException {
    return readString(byteCount, StandardCharsets.UTF_8);
  }

  @Override public String readString(Charset charset) {
    try {
      return readString(size, charset);
    } catch (EOFException e) {
      throw new AssertionError(e);
    }
  }

  @Override public String readString(int byteCount, Charset charset) throws EOFException {
    Util.checkOffsetAndCount(size, 0, byteCount);
    if (charset == null) throw new IllegalArgumentException("charset == null");
    if (byteCount == 0) return "";

    Segment s = head;
    if (s.pos + byteCount > s.limit) {
      // If the string spans multiple segments, delegate to readBytes().
      return new String(readByteArray(byteCount), charset);
    }

    String result = new String(s.data, s.pos, byteCount, charset);
    s.pos += byteCount;
    size -= byteCount;

    if (s.pos == s.limit) {
      head = s.pop();
      SegmentPool.recycle(s);
    }

    return result;
  }

  @Override public String readUtf8LineStrict() throws EOFException {
    return readUtf8LineStrict(Integer.MAX_VALUE);
  }

  @Override public String readUtf8LineStrict(int limit) throws EOFException {
    if (limit < 0) throw new IllegalArgumentException("limit < 0: " + limit);
    int scanLength = limit == Integer.MAX_VALUE ? Integer.MAX_VALUE : limit + 1;
    int newline = indexOf((byte) '\n', 0, scanLength);
    if (newline != -1) return readUtf8Line(newline);
    if (scanLength < size()
        && getByte(scanLength - 1) == '\r' && getByte(scanLength) == '\n') {
      return readUtf8Line(scanLength); // The line was 'limit' UTF-8 bytes followed by \r\n.
    }
    Buffer data = new Buffer();
    copyTo(data, 0, Math.min(32, size()));
    throw new EOFException("\\n not found: limit=" + Math.min(size(), limit)
        + " content=" + HexFormat.of().formatHex(data.readByteArray()) + '…');
  }

  String readUtf8Line(int newline) throws EOFException {
    if (newline > 0 && getByte(newline - 1) == '\r') {
      // Read everything until '\r\n', then skip the '\r\n'.
      String result = readUtf8((newline - 1));
      skip(2);
      return result;

    } else {
      // Read everything until '\n', then skip the '\n'.
      String result = readUtf8(newline);
      skip(1);
      return result;
    }
  }

  @Override public byte[] readByteArray() {
    try {
      return readByteArray(size);
    } catch (EOFException e) {
      throw new AssertionError(e);
    }
  }

  public byte[] readByteArray(int byteCount) throws EOFException {
    Util.checkOffsetAndCount(size, 0, byteCount);

    byte[] result = new byte[byteCount];
    readFully(result);
    return result;
  }

  @Override public void readFully(byte[] sink) throws EOFException {
    int offset = 0;
    while (offset < sink.length) {
      int read = read(sink, offset, sink.length - offset);
      if (read == -1) throw new EOFException();
      offset += read;
    }
  }

  public int read(byte[] sink, int offset, int byteCount) {
    Util.checkOffsetAndCount(sink.length, offset, byteCount);

    Segment s = head;
    if (s == null) return -1;
    int toCopy = Math.min(byteCount, s.limit - s.pos);
    System.arraycopy(s.data, s.pos, sink, offset, toCopy);

    s.pos += toCopy;
    size -= toCopy;

    if (s.pos == s.limit) {
      head = s.pop();
      SegmentPool.recycle(s);
    }

    return toCopy;
  }

  /**
   * Discards all bytes in this buffer. Calling this method when you're done
   * with a buffer will return its segments to the pool.
   */
  public void clear() {
    try {
      skip(size);
    } catch (EOFException e) {
      throw new AssertionError(e);
    }
  }

  /** Discards {@code byteCount} bytes from the head of this buffer. */
  @Override public void skip(int byteCount) throws EOFException {
    while (byteCount > 0) {
      if (head == null) throw new EOFException();

      int toSkip = Math.min(byteCount, head.limit - head.pos);
      size -= toSkip;
      byteCount -= toSkip;
      head.pos += toSkip;

      if (head.pos == head.limit) {
        Segment toRecycle = head;
        head = toRecycle.pop();
        SegmentPool.recycle(toRecycle);
      }
    }
  }

  @Override public Buffer writeUtf8(String string) {
    return writeUtf8(string, 0, string.length());
  }

  @Override public Buffer writeUtf8(String string, int beginIndex, int endIndex) {
    if (string == null) throw new IllegalArgumentException("string == null");
    if (beginIndex < 0) throw new IllegalArgumentException("beginIndex < 0: " + beginIndex);
    if (endIndex < beginIndex) {
      throw new IllegalArgumentException("endIndex < beginIndex: " + endIndex + " < " + beginIndex);
    }
    if (endIndex > string.length()) {
      throw new IllegalArgumentException(
          "endIndex > string.length: " + endIndex + " > " + string.length());
    }

    // Transcode a UTF-16 Java String to UTF-8 bytes.
    for (int i = beginIndex; i < endIndex;) {
      int c = string.charAt(i);

      if (c < 0x80) {
        Segment tail = writableSegment(1);
        byte[] data = tail.data;
        int segmentOffset = tail.limit - i;
        int runLimit = Math.min(endIndex, Segment.SIZE - segmentOffset);

        // Emit a 7-bit character with 1 byte.
        data[segmentOffset + i++] = (byte) c; // 0xxxxxxx

        // Fast-path contiguous runs of ASCII characters. This is ugly, but yields a ~4x performance
        // improvement over independent calls to writeByte().
        while (i < runLimit) {
          c = string.charAt(i);
          if (c >= 0x80) break;
          data[segmentOffset + i++] = (byte) c; // 0xxxxxxx
        }

        int runSize = i + segmentOffset - tail.limit; // Equivalent to i - (previous i).
        tail.limit += runSize;
        size += runSize;

      } else if (c < 0x800) {
        // Emit a 11-bit character with 2 bytes.
        writeByte(c >>  6        | 0xc0); // 110xxxxx
        writeByte(c       & 0x3f | 0x80); // 10xxxxxx
        i++;

      } else if (c < 0xd800 || c > 0xdfff) {
        // Emit a 16-bit character with 3 bytes.
        writeByte(c >> 12        | 0xe0); // 1110xxxx
        writeByte(c >>  6 & 0x3f | 0x80); // 10xxxxxx
        writeByte(c       & 0x3f | 0x80); // 10xxxxxx
        i++;

      } else {
        // c is a surrogate. Make sure it is a high surrogate & that its successor is a low
        // surrogate. If not, the UTF-16 is invalid, in which case we emit a replacement character.
        int low = i + 1 < endIndex ? string.charAt(i + 1) : 0;
        if (c > 0xdbff || low < 0xdc00 || low > 0xdfff) {
          writeByte('?');
          i++;
          continue;
        }

        // UTF-16 high surrogate: 110110xxxxxxxxxx (10 bits)
        // UTF-16 low surrogate:  110111yyyyyyyyyy (10 bits)
        // Unicode code point:    00010000000000000000 + xxxxxxxxxxyyyyyyyyyy (21 bits)
        int codePoint = 0x010000 + ((c & ~0xd800) << 10 | low & ~0xdc00);

        // Emit a 21-bit character with 4 bytes.
        writeByte(codePoint >> 18        | 0xf0); // 11110xxx
        writeByte(codePoint >> 12 & 0x3f | 0x80); // 10xxxxxx
        writeByte(codePoint >>  6 & 0x3f | 0x80); // 10xxyyyy
        writeByte(codePoint       & 0x3f | 0x80); // 10yyyyyy
        i += 2;
      }
    }

    return this;
  }

  @Override public void writeUtf8CodePoint(int codePoint) {
    if (codePoint < 0x80) {
      // Emit a 7-bit code point with 1 byte.
      writeByte(codePoint);

    } else if (codePoint < 0x800) {
      // Emit a 11-bit code point with 2 bytes.
      writeByte(codePoint >>  6        | 0xc0); // 110xxxxx
      writeByte(codePoint       & 0x3f | 0x80); // 10xxxxxx

    } else if (codePoint < 0x10000) {
      if (codePoint >= 0xd800 && codePoint <= 0xdfff) {
        // Emit a replacement character for a partial surrogate.
        writeByte('?');
      } else {
        // Emit a 16-bit code point with 3 bytes.
        writeByte(codePoint >> 12        | 0xe0); // 1110xxxx
        writeByte(codePoint >>  6 & 0x3f | 0x80); // 10xxxxxx
        writeByte(codePoint       & 0x3f | 0x80); // 10xxxxxx
      }

    } else if (codePoint <= 0x10ffff) {
      // Emit a 21-bit code point with 4 bytes.
      writeByte(codePoint >> 18        | 0xf0); // 11110xxx
      writeByte(codePoint >> 12 & 0x3f | 0x80); // 10xxxxxx
      writeByte(codePoint >>  6 & 0x3f | 0x80); // 10xxxxxx
      writeByte(codePoint       & 0x3f | 0x80); // 10xxxxxx

    } else {
      throw new IllegalArgumentException(
          "Unexpected code point: " + Integer.toHexString(codePoint));
    }

  }

  @Override public Buffer write(byte[] source) {
    if (source == null) throw new IllegalArgumentException("source == null");
    return write(source, 0, source.length);
  }

  @Override public Buffer write(byte[] source, int offset, int byteCount) {
    if (source == null) throw new IllegalArgumentException("source == null");
    Util.checkOffsetAndCount(source.length, offset, byteCount);

    int limit = offset + byteCount;
    while (offset < limit) {
      Segment tail = writableSegment(1);

      int toCopy = Math.min(limit - offset, Segment.SIZE - tail.limit);
      System.arraycopy(source, offset, tail.data, tail.limit, toCopy);

      offset += toCopy;
      tail.limit += toCopy;
    }

    size += byteCount;
    return this;
  }

  @Override public void writeAll(Source source) throws IOException {
    if (source == null) throw new IllegalArgumentException("source == null");
    while (source.read(this, Segment.SIZE) != -1) {
      // exhausting the source
    }
  }

  @Override public BufferedSink write(Source source, int byteCount) throws IOException {
    while (byteCount > 0) {
      int read = source.read(this, byteCount);
      if (read == -1) throw new EOFException();
      byteCount -= read;
    }
    return this;
  }

  @Override public Buffer writeByte(int b) {
    Segment tail = writableSegment(1);
    tail.data[tail.limit++] = (byte) b;
    size += 1;
    return this;
  }

  @Override public Buffer writeHexadecimalInt(int v) {
    if (v == 0) {
      // Both a shortcut and required since the following code can't handle zero.
      return writeByte('0');
    }

    int width = Integer.numberOfTrailingZeros(Integer.highestOneBit(v)) / 4 + 1;

    Segment tail = writableSegment(width);
    byte[] data = tail.data;
    for (int pos = tail.limit + width - 1, start = tail.limit; pos >= start; pos--) {
      data[pos] = DIGITS[v & 0xF];
      v >>>= 4;
    }
    tail.limit += width;
    size += width;
    return this;
  }

  /**
   * Returns a tail segment that we can write at least {@code minimumCapacity}
   * bytes to, creating it if necessary.
   */
  Segment writableSegment(int minimumCapacity) {
    if (minimumCapacity < 1 || minimumCapacity > Segment.SIZE) throw new IllegalArgumentException();

    if (head == null) {
      head = SegmentPool.take(); // Acquire a first segment.
      return head.next = head.prev = head;
    }

    Segment tail = head.prev;
    if (tail.limit + minimumCapacity > Segment.SIZE || !tail.owner) {
      tail = tail.push(SegmentPool.take()); // Append a new empty segment to fill up.
    }
    return tail;
  }

  @Override public void write(Buffer source, int byteCount) {
    // Move bytes from the head of the source buffer to the tail of this buffer
    // while balancing two conflicting goals: don't waste CPU and don't waste
    // memory.
    //
    //
    // Don't waste CPU (ie. don't copy data around).
    //
    // Copying large amounts of data is expensive. Instead, we prefer to
    // reassign entire segments from one buffer to the other.
    //
    //
    // Don't waste memory.
    //
    // As an invariant, adjacent pairs of segments in a buffer should be at
    // least 50% full, except for the head segment and the tail segment.
    //
    // The head segment cannot maintain the invariant because the application is
    // consuming bytes from this segment, decreasing its level.
    //
    // The tail segment cannot maintain the invariant because the application is
    // producing bytes, which may require new nearly-empty tail segments to be
    // appended.
    //
    //
    // Moving segments between buffers
    //
    // When writing one buffer to another, we prefer to reassign entire segments
    // over copying bytes into their most compact form. Suppose we have a buffer
    // with these segment levels [91%, 61%]. If we append a buffer with a
    // single [72%] segment, that yields [91%, 61%, 72%]. No bytes are copied.
    //
    // Or suppose we have a buffer with these segment levels: [100%, 2%], and we
    // want to append it to a buffer with these segment levels [99%, 3%]. This
    // operation will yield the following segments: [100%, 2%, 99%, 3%]. That
    // is, we do not spend time copying bytes around to achieve more efficient
    // memory use like [100%, 100%, 4%].
    //
    // When combining buffers, we will compact adjacent buffers when their
    // combined level doesn't exceed 100%. For example, when we start with
    // [100%, 40%] and append [30%, 80%], the result is [100%, 70%, 80%].
    //
    //
    // Splitting segments
    //
    // Occasionally we write only part of a source buffer to a sink buffer. For
    // example, given a sink [51%, 91%], we may want to write the first 30% of
    // a source [92%, 82%] to it. To simplify, we first transform the source to
    // an equivalent buffer [30%, 62%, 82%] and then move the head segment,
    // yielding sink [51%, 91%, 30%] and source [62%, 82%].

    if (source == null) throw new IllegalArgumentException("source == null");
    if (source == this) throw new IllegalArgumentException("source == this");
    Util.checkOffsetAndCount(source.size, 0, byteCount);

    while (byteCount > 0) {
      // Is a prefix of the source's head segment all that we need to move?
      if (byteCount < (source.head.limit - source.head.pos)) {
        Segment tail = head != null ? head.prev : null;
        if (tail != null && tail.owner
            && (byteCount + tail.limit - (tail.shared ? 0 : tail.pos) <= Segment.SIZE)) {
          // Our existing segments are sufficient. Move bytes from source's head to our tail.
          source.head.writeTo(tail, byteCount);
          source.size -= byteCount;
          size += byteCount;
          return;
        } else {
          // We're going to need another segment. Split the source's head
          // segment in two, then move the first of those two to this buffer.
          source.head = source.head.split(byteCount);
        }
      }

      // Remove the source's head segment and append it to our tail.
      Segment segmentToMove = source.head;
      int movedByteCount = segmentToMove.limit - segmentToMove.pos;
      source.head = segmentToMove.pop();
      if (head == null) {
        head = segmentToMove;
        head.next = head.prev = head;
      } else {
        Segment tail = head.prev;
        tail = tail.push(segmentToMove);
        tail.compact();
      }
      source.size -= movedByteCount;
      size += movedByteCount;
      byteCount -= movedByteCount;
    }
  }

  @Override public int read(Buffer sink, int byteCount) {
    if (sink == null) throw new IllegalArgumentException("sink == null");
    if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
    if (size == 0) return -1;
    if (byteCount > size) byteCount = size;
    sink.write(this, byteCount);
    return byteCount;
  }

  @Override public int indexOf(byte b) {
    return indexOf(b, 0, Integer.MAX_VALUE);
  }

  @Override public int indexOf(byte b, int fromIndex, int toIndex) {
    if (fromIndex < 0 || toIndex < fromIndex) {
      throw new IllegalArgumentException(
          String.format("size=%s fromIndex=%s toIndex=%s", size, fromIndex, toIndex));
    }

    if (toIndex > size) toIndex = size;
    if (fromIndex == toIndex) return -1;

    Segment s;
    int offset;

    // TODO(jwilson): extract this to a shared helper method when can do so without allocating.
    // Pick the first segment to scan. This is the first segment with offset <= fromIndex.
    s = head;
    if (s == null) {
      // No segments to scan!
      return -1;
    } else if (size - fromIndex < fromIndex) {
      // We're scanning in the back half of this buffer. Find the segment starting at the back.
      offset = size;
      while (offset > fromIndex) {
        s = s.prev;
        offset -= (s.limit - s.pos);
      }
    } else {
      // We're scanning in the front half of this buffer. Find the segment starting at the front.
      offset = 0;
      for (int nextOffset; (nextOffset = offset + (s.limit - s.pos)) < fromIndex; ) {
        s = s.next;
        offset = nextOffset;
      }
    }

    // Scan through the segments, searching for b.
    while (offset < toIndex) {
      byte[] data = s.data;
      int limit = Math.min(s.limit, s.pos + toIndex - offset);
      int pos = s.pos + fromIndex - offset;
      for (; pos < limit; pos++) {
        if (data[pos] == b) {
          return pos - s.pos + offset;
        }
      }

      // Not in this segment. Try the next one.
      offset += (s.limit - s.pos);
      fromIndex = offset;
      s = s.next;
    }

    return -1;
  }

  @Override public void flush() {
  }

  @Override public void close() {
  }

  @Override public Timeout timeout() {
    return Timeout.NONE;
  }

  @Override public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Buffer that)) return false;
    if (size != that.size) return false;
    if (size == 0) return true; // Both buffers are empty.

    Segment sa = this.head;
    Segment sb = that.head;
    int posA = sa.pos;
    int posB = sb.pos;

    for (int pos = 0, count; pos < size; pos += count) {
      count = Math.min(sa.limit - posA, sb.limit - posB);

      for (int i = 0; i < count; i++) {
        if (sa.data[posA++] != sb.data[posB++]) return false;
      }

      if (posA == sa.limit) {
        sa = sa.next;
        posA = sa.pos;
      }

      if (posB == sb.limit) {
        sb = sb.next;
        posB = sb.pos;
      }
    }

    return true;
  }

  @Override public int hashCode() {
    Segment s = head;
    if (s == null) return 0;
    int result = 1;
    do {
      for (int pos = s.pos, limit = s.limit; pos < limit; pos++) {
        result = 31 * result + s.data[pos];
      }
      s = s.next;
    } while (s != head);
    return result;
  }

  @Override public Buffer clone() {
    Buffer result = new Buffer();
    if (size == 0) return result;

    result.head = head.sharedCopy();
    result.head.next = result.head.prev = result.head;
    for (Segment s = head.next; s != head; s = s.next) {
      result.head.prev.push(s.sharedCopy());
    }
    result.size = size;
    return result;
  }
}
