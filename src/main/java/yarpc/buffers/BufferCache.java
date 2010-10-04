package yarpc.buffers;

import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Cache of reusable ByteBuffers.  Will attempt to cleanup if size drifts above maxSize -- maxSize is not a hard guarantee.
 * Implemented using ConcurrentSkipListMap and ReadWriteLock internally.    
 * gets and puts use readlock -- cleanup uses write lock and blocks all activity -- it will run quick though,
 * because it's just a mass dereference spread evenly across the stack
 * */
public class BufferCache {
  private final ConcurrentNavigableMap<Integer, BlockingDeque<ByteBuffer>> buffers = new ConcurrentSkipListMap<Integer, BlockingDeque<ByteBuffer>>();
  private final Lock cleanupLock = new ReentrantLock(false);
  private final long maxSize;
  private final AtomicLong currentSize = new AtomicLong(0);  
  private final ExecutorService cleanupThread = Executors.newFixedThreadPool(1);
  private final ReadWriteLock lock = new ReentrantReadWriteLock(true);
  private final Lock r = lock.readLock();
  private final Lock w = lock.writeLock();
  
  public BufferCache(long maxSize) {
    this.maxSize=maxSize;
  }
  
  /** Returns a cleared ByteBuffer of at least size.  Limit has been set to size.  */
  public ByteBuffer get(int size) {
    Map.Entry<Integer, BlockingDeque<ByteBuffer>> entry = buffers.ceilingEntry(size);
    if (entry == null) {
      // if we didn't have any arrays big enough, return new array
      return ByteBuffer.allocate(size);
    }
    r.lock();
    try {
      ByteBuffer ret = entry.getValue().poll();
      if (ret != null) {
        currentSize.addAndGet( (0 - ret.capacity()) );
        ret.clear();
        ret.limit(size);
        return ret;
      }
      else return ByteBuffer.allocate(size);
    } finally {
      r.unlock();
    }

  }
  
  /** Caches the supplied ByteBuffer in it's proper size bucket */
  public void put(ByteBuffer buff) {
    r.lock();
    long size;
    try {
      Integer buffSize = buff.capacity();
      BlockingDeque<ByteBuffer> deque = buffers.get(buffSize);
      if (deque == null) {
        buffers.putIfAbsent(buffSize, new LinkedBlockingDeque<ByteBuffer>());
        deque = buffers.get(buffSize);
      }
      try {
        deque.put(buff);
      } catch (InterruptedException e) {} // dereference
      // capture to check for cleanup
      size = currentSize.addAndGet(buffSize);
    } finally {
      r.unlock();
    }
    if (size > maxSize) {
      // cleanup if necessary
      cleanup();
    }
  }
  
  private void cleanup() {
    // skip lock acquisition if we're already cleaned up
    if (currentSize.get() < maxSize) return;
    w.lock();
    try {
      int counter = 0;
      while (currentSize.get() > maxSize) {
        long newSize = 0;
        for (Map.Entry<Integer,BlockingDeque<ByteBuffer>> entry : buffers.entrySet()) {
          int qLen = entry.getValue().size();
          //remove empty deques and up to 1/5 inhabited ones, evenly distributed
          if (qLen == 0 || counter % 5 == 0) buffers.remove(entry.getKey());
          else newSize += qLen * entry.getKey();
        }
        currentSize.set(newSize);
      }
    } finally {
      w.unlock();
    }
  }
  
  /** Approximate total size of buffers cached */
  private int size() { 
    int ret = 0;
    for (int s : buffers.keySet()) ret+= s;
    return ret;
  }
  
}
