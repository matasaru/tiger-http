package kio;

import java.util.concurrent.TimeUnit;

/**
 * A timeout that can apply its own state to another timeout for the duration
 * of a call. Use this when forwarding one stream to another while retaining
 * both timeouts.
 */
final class PushableTimeout extends Timeout {
  private Timeout pushed;
  private boolean originalHasDeadline;
  private long originalDeadlineNanoTime;
  private long originalTimeoutNanos;

  void push(Timeout pushed) {
    this.pushed = pushed;
    this.originalHasDeadline = pushed.hasDeadline();
    this.originalDeadlineNanoTime = originalHasDeadline ? pushed.deadlineNanoTime() : -1L;
    this.originalTimeoutNanos = pushed.timeoutNanos();

    pushed.timeout(minTimeout(originalTimeoutNanos, timeoutNanos()), TimeUnit.NANOSECONDS);

    if (originalHasDeadline && hasDeadline()) {
      pushed.deadlineNanoTime(Math.min(deadlineNanoTime(), originalDeadlineNanoTime));
    } else if (hasDeadline()) {
      pushed.deadlineNanoTime(deadlineNanoTime());
    }
  }

  void pop() {
    pushed.timeout(originalTimeoutNanos, TimeUnit.NANOSECONDS);

    if (originalHasDeadline) {
      pushed.deadlineNanoTime(originalDeadlineNanoTime);
    } else {
      pushed.clearDeadline();
    }
  }
}
