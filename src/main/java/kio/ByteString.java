package kio;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * An immutable sequence of bytes.
 *
 * <p>Byte strings compare lexicographically as a sequence of <strong>unsigned</strong> bytes. That
 * is, the byte string {@code ff} sorts after {@code 00}. This is counter to the sort order of the
 * corresponding bytes, where {@code -1} sorts before {@code 0}.
 *
 * <p><strong>Full disclosure:</strong> this class provides untrusted input and output streams with
 * raw access to the underlying byte array. A hostile stream implementation could keep a reference
 * to the mutable byte string, violating the immutable guarantee of this class. For this reason a
 * byte string's immutability guarantee cannot be relied upon for security in applets and other
 * environments that run both trusted and untrusted code in the same process.
 */
public class ByteString implements Serializable, Comparable<ByteString> {

  static final char[] HEX_DIGITS = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

  @Serial
  private static final long serialVersionUID = 1L;

  /** A singleton empty {@code ByteString}. */
  public static final ByteString EMPTY = new ByteString(new byte[]{});

  final byte[] data;
  transient int hashCode; // Lazily computed; 0 if unknown.
  transient String utf8; // Lazily computed.

  ByteString(byte[] data) {
    this.data = data; // Trusted internal constructor doesn't clone data.
  }

  /** Returns a new byte string containing the {@code UTF-8} bytes of {@code s}. */
  public static ByteString encodeUtf8(String s) {
    if (s == null) throw new IllegalArgumentException("s == null");
    ByteString byteString = new ByteString(s.getBytes(StandardCharsets.UTF_8));
    byteString.utf8 = s;
    return byteString;
  }

  /** Constructs a new {@code String} by decoding the bytes as {@code UTF-8}. */
  public String utf8() {
    String result = utf8;
    // We don't care if we double-allocate in racy code.
    return result != null ? result : (utf8 = new String(data, StandardCharsets.UTF_8));
  }

  /** Returns this byte string encoded in hexadecimal. */
  public String hex() {
    char[] result = new char[data.length * 2];
    int c = 0;
    for (byte b : data) {
      result[c++] = HEX_DIGITS[(b >> 4) & 0xf];
      result[c++] = HEX_DIGITS[b & 0xf];
    }
    return new String(result);
  }

  /**
   * Returns a byte string that is a substring of this byte string, beginning at the specified
   * {@code beginIndex} and ends at the specified {@code endIndex}. Returns this byte string if
   * {@code beginIndex} is 0 and {@code endIndex} is the length of this byte string.
   */
  public ByteString substring(int beginIndex, int endIndex) {
    if (beginIndex < 0) throw new IllegalArgumentException("beginIndex < 0");
    if (endIndex > data.length) {
      throw new IllegalArgumentException("endIndex > length(" + data.length + ")");
    }

    int subLen = endIndex - beginIndex;
    if (subLen < 0) throw new IllegalArgumentException("endIndex < beginIndex");

    if ((beginIndex == 0) && (endIndex == data.length)) {
      return this;
    }

    byte[] copy = new byte[subLen];
    System.arraycopy(data, beginIndex, copy, 0, subLen);
    return new ByteString(copy);
  }

  /** Returns the byte at {@code pos}. */
  public byte getByte(int pos) {
    return data[pos];
  }

  /**
   * Returns the number of bytes in this ByteString.
   */
  public int size() {
    return data.length;
  }

  /** Returns the bytes of this string without a defensive copy. Do not mutate! */
  byte[] internalArray() {
    return data;
  }

  /**
   * Returns true if the bytes of this in {@code [offset..offset+byteCount)} equal the bytes of
   * {@code other} in {@code [otherOffset..otherOffset+byteCount)}. Returns false if either range is
   * out of bounds.
   */
  public boolean rangeEquals(int offset, ByteString other, int otherOffset, int byteCount) {
    return other.rangeEquals(otherOffset, this.data, offset, byteCount);
  }

  /**
   * Returns true if the bytes of this in {@code [offset..offset+byteCount)} equal the bytes of
   * {@code other} in {@code [otherOffset..otherOffset+byteCount)}. Returns false if either range is
   * out of bounds.
   */
  public boolean rangeEquals(int offset, byte[] other, int otherOffset, int byteCount) {
    return offset >= 0 && offset <= data.length - byteCount
        && otherOffset >= 0 && otherOffset <= other.length - byteCount
        && Util.arrayRangeEquals(data, offset, other, otherOffset, byteCount);
  }

  public final boolean startsWith(ByteString prefix) {
    return rangeEquals(0, prefix, 0, prefix.size());
  }

  public final boolean startsWith(byte[] prefix) {
    return rangeEquals(0, prefix, 0, prefix.length);
  }

  public final int indexOf(ByteString other) {
    return indexOf(other.internalArray(), 0);
  }

  public final int indexOf(ByteString other, int fromIndex) {
    return indexOf(other.internalArray(), fromIndex);
  }

  public final int indexOf(byte[] other) {
    return indexOf(other, 0);
  }

  public int indexOf(byte[] other, int fromIndex) {
    fromIndex = Math.max(fromIndex, 0);
    for (int i = fromIndex, limit = data.length - other.length; i <= limit; i++) {
      if (Util.arrayRangeEquals(data, i, other, 0, other.length)) {
        return i;
      }
    }
    return -1;
  }

  @Override public boolean equals(Object o) {
    if (o == this) return true;
    return o instanceof ByteString
        && ((ByteString) o).size() == data.length
        && ((ByteString) o).rangeEquals(0, data, 0, data.length);
  }

  @Override public int hashCode() {
    int result = hashCode;
    return result != 0 ? result : (hashCode = Arrays.hashCode(data));
  }

  @Override public int compareTo(ByteString byteString) {
    int sizeA = size();
    int sizeB = byteString.size();
    for (int i = 0, size = Math.min(sizeA, sizeB); i < size; i++) {
      int byteA = getByte(i) & 0xff;
      int byteB = byteString.getByte(i) & 0xff;
      if (byteA == byteB) continue;
      return byteA < byteB ? -1 : 1;
    }
    if (sizeA == sizeB) return 0;
    return sizeA < sizeB ? -1 : 1;
  }

  /**
   * Returns a human-readable string that describes the contents of this byte string. Typically this
   * is a string like {@code [text=Hello]} or {@code [hex=0000ffff]}.
   */
  @Override public String toString() {
    if (data.length == 0) {
      return "[size=0]";
    }

    String text = utf8();
    int i = codePointIndexToCharIndex(text);

    if (i == -1) {
      return data.length <= 64
          ? "[hex=" + hex() + "]"
          : "[size=" + data.length + " hex=" + substring(0, 64).hex() + "…]";
    }

    String safeText = text.substring(0, i)
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
    return i < text.length()
        ? "[size=" + data.length + " text=" + safeText + "…]"
        : "[text=" + safeText + "]";
  }

  static int codePointIndexToCharIndex(String s) {
    for (int i = 0, j = 0, length = s.length(), c; i < length; i += Character.charCount(c)) {
      if (j == 64) {
        return i;
      }
      c = s.codePointAt(i);
      if ((Character.isISOControl(c) && c != '\n' && c != '\r')
          || c == Buffer.REPLACEMENT_CHARACTER) {
        return -1;
      }
      j++;
    }
    return s.length();
  }

  @Serial
  private void readObject(ObjectInputStream in) throws IOException {
    int dataLength = in.readInt();
    if (dataLength < 0) throw new IllegalArgumentException("byteCount < 0: " + dataLength);

    byte[] result = new byte[dataLength];
    for (int offset = 0, read; offset < dataLength; offset += read) {
      read = in.read(result, offset, dataLength - offset);
      if (read == -1) throw new EOFException();
    }

    try {
      Field field = ByteString.class.getDeclaredField("data");
      field.setAccessible(true);
      field.set(this, result);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new AssertionError();
    }
  }

  @Serial
  private void writeObject(ObjectOutputStream out) throws IOException {
    out.writeInt(data.length);
    out.write(data);
  }
}
