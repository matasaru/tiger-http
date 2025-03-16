package kio;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Essential APIs for working with Okio. */
public final class Okio {
  static final Logger logger = Logger.getLogger(Okio.class.getName());

  private Okio() {
  }

  private static Sink sink(final OutputStream out, final Timeout timeout) {
    if (out == null) throw new IllegalArgumentException("out == null");
    if (timeout == null) throw new IllegalArgumentException("timeout == null");

    return new Sink() {
      @Override public void write(Buffer source, int byteCount) throws IOException {
        Util.checkOffsetAndCount(source.size, 0, byteCount);
        while (byteCount > 0) {
          timeout.throwIfReached();
          Segment head = source.head;
          int toCopy = Math.min(byteCount, head.limit - head.pos);
          out.write(head.data, head.pos, toCopy);

          head.pos += toCopy;
          byteCount -= toCopy;
          source.size -= toCopy;

          if (head.pos == head.limit) {
            source.head = head.pop();
            SegmentPool.recycle(head);
          }
        }
      }

      @Override public void flush() throws IOException {
        out.flush();
      }

      @Override public void close() throws IOException {
        out.close();
      }

      @Override public Timeout timeout() {
        return timeout;
      }

      @Override public String toString() {
        return "sink(" + out + ")";
      }
    };
  }

  /**
   * Returns a sink that writes to {@code socket}. When the socket
   * write times out, the socket is asynchronously closed by a watchdog thread.
   */
  public static Sink sink(Socket socket) throws IOException {
    if (socket == null) throw new IllegalArgumentException("socket == null");
    if (socket.getOutputStream() == null) throw new IOException("socket's output stream == null");
    AsyncTimeout timeout = timeout(socket);
    Sink sink = sink(socket.getOutputStream(), timeout);
    return timeout.sink(sink);
  }

  /** Returns a source that reads from {@code in}. */
  public static Source source(InputStream in) {
    return source(in, new Timeout());
  }

  private static Source source(final InputStream in, final Timeout timeout) {
    if (in == null) throw new IllegalArgumentException("in == null");
    if (timeout == null) throw new IllegalArgumentException("timeout == null");

    return new Source() {
      @Override public int read(Buffer sink, int byteCount) throws IOException {
        if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
        if (byteCount == 0) return 0;
        timeout.throwIfReached();
        Segment tail = sink.writableSegment(1);
        int maxToCopy = Math.min(byteCount, Segment.SIZE - tail.limit);
        int bytesRead = in.read(tail.data, tail.limit, maxToCopy);
        if (bytesRead == -1) {
          if (tail.pos == tail.limit) {
            // We allocated a tail segment, but didn't end up needing it. Recycle!
            sink.head = tail.pop();
            SegmentPool.recycle(tail);
          }
          return -1;
        }
        tail.limit += bytesRead;
        sink.size += bytesRead;
        return bytesRead;
      }

      @Override public void close() throws IOException {
        in.close();
      }

      @Override public Timeout timeout() {
        return timeout;
      }

      @Override public String toString() {
        return "source(" + in + ")";
      }
    };
  }

  /**
   * Returns a source that reads from {@code socket}. Prefer this over {@link
   * #source(InputStream)} because this method honors timeouts. When the socket
   * read times out, the socket is asynchronously closed by a watchdog thread.
   */
  public static Source source(Socket socket) throws IOException {
    if (socket == null) throw new IllegalArgumentException("socket == null");
    if (socket.getInputStream() == null) throw new IOException("socket's input stream == null");
    AsyncTimeout timeout = timeout(socket);
    Source source = source(socket.getInputStream(), timeout);
    return timeout.source(source);
  }

  private static AsyncTimeout timeout(final Socket socket) {
    return new AsyncTimeout() {
      @Override protected IOException newTimeoutException(IOException cause) {
        InterruptedIOException ioe = new SocketTimeoutException("timeout");
        if (cause != null) {
          ioe.initCause(cause);
        }
        return ioe;
      }

      @Override protected void timedOut() {
        try {
          socket.close();
        } catch (Exception e) {
          logger.log(Level.WARNING, "Failed to close timed out socket " + socket, e);
        }
      }
    };
  }
}
