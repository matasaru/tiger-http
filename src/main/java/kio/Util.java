package kio;

final class Util {

  private Util() {
  }

  public static void checkOffsetAndCount(int size, int offset, int byteCount) {
    if ((offset | byteCount) < 0 || offset > size || size - offset < byteCount) {
      throw new ArrayIndexOutOfBoundsException(
          String.format("size=%s offset=%s byteCount=%s", size, offset, byteCount));
    }
  }

  /**
   * Throws {@code t}, even if the declared throws clause doesn't permit it.
   * This is a terrible – but terribly convenient – hack that makes it easy to
   * catch and rethrow exceptions after cleanup. See Java Puzzlers #43.
   */
  public static void sneakyRethrow(Throwable t) {
    Util.<Error>sneakyThrow2(t);
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void sneakyThrow2(Throwable t) throws T {
    throw (T) t;
  }

  public static boolean arrayRangeEquals(
      byte[] a, int aOffset, byte[] b, int bOffset, int byteCount) {
    for (int i = 0; i < byteCount; i++) {
      if (a[i + aOffset] != b[i + bOffset]) return false;
    }
    return true;
  }
}
