package kio;

import java.io.IOException;

/** A {@link Source} which forwards calls to another. Useful for subclassing. */
public abstract class ForwardingSource implements Source {
  private final Source delegate;

  public ForwardingSource(Source delegate) {
    if (delegate == null) throw new IllegalArgumentException("delegate == null");
    this.delegate = delegate;
  }

  @Override public int read(Buffer sink, int byteCount) throws IOException {
    return delegate.read(sink, byteCount);
  }

  @Override public Timeout timeout() {
    return delegate.timeout();
  }

  @Override public void close() throws IOException {
    delegate.close();
  }

  @Override public String toString() {
    return getClass().getSimpleName() + "(" + delegate + ")";
  }
}
