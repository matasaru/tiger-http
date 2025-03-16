package kio;

import java.io.IOException;

/** A {@link Sink} which forwards calls to another. Useful for subclassing. */
public abstract class ForwardingSink implements Sink {
  private final Sink delegate;

  public ForwardingSink(Sink delegate) {
    if (delegate == null) throw new IllegalArgumentException("delegate == null");
    this.delegate = delegate;
  }

  @Override public void write(Buffer source, int byteCount) throws IOException {
    delegate.write(source, byteCount);
  }

  @Override public void flush() throws IOException {
    delegate.flush();
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
