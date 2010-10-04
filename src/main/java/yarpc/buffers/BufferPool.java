package yarpc.buffers;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class BufferPool {
  private final int bufferSize;
  private final int numBuffers;
  private final ConcurrentLinkedQueue<PooledBuffer> q;
  private final AtomicInteger numInQueue = new AtomicInteger(0);
  
  public BufferPool(int bufferSize, int numBuffers) {
    this.bufferSize=bufferSize;
    this.numBuffers=numBuffers;
    q = new ConcurrentLinkedQueue<PooledBuffer>();
  }
  
  /**
   * Returns a PooledBuffer limited to minSize with a reference count of 1.
   * 
   * @param minSize
   * @return
   */
  public PooledBuffer getBuff(int minSize) {
    if (minSize > bufferSize) {
      throw new IllegalArgumentException(
          "Requested buffer of size " + minSize + " " +
          		"exceeds configured bufferSize of " + bufferSize + "!");
    }
    PooledBuffer fromPool = q.poll();
    if (fromPool != null) {
      numInQueue.decrementAndGet();
      fromPool.buff().clear();
      fromPool.buff().limit(bufferSize);
      // add one reference for caller
      fromPool.ref();
      return fromPool;
    } else {
      return new PooledBuffer(this, ByteBuffer.allocate(this.bufferSize));
    }
  }

  void returnBuff(PooledBuffer pooledBuffer) {
    // bump queue count, if full, return
    if (numInQueue.incrementAndGet() >= numBuffers) return;
    // busy loop to add otherwise
    while (q.add(pooledBuffer) == false) {}
  }
  
  
}
