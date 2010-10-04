package yarpc.buffers;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

public class PooledBuffer {
  private final BufferPool pool;
  private final ByteBuffer buff;
  private final AtomicLong refCount = new AtomicLong(0);
  
  public PooledBuffer(BufferPool p, ByteBuffer b) {
    pool=p;
    buff=b;
  }
  
  /** Returns a reference to the buffer */
  public ByteBuffer buff() { return buff; }
  
  /** Increments the reference count.  Returns current refCount. */
  public long ref() { return refCount.incrementAndGet(); }
  
  /** Decrements the reference count and, if appropriate, returns this buffer to the pool it was constructed from.  */
  public long deref() {  
    long c = refCount.decrementAndGet();
    if (c == 0) {
      pool.returnBuff(this);
    }
    return c;
  }
}
