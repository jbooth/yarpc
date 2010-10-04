package yarpc.client;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Static singleton representing global connection pool
 */
class GlobalConnPool {
 
  
//  private static class ResourcePool<T> {
//    private final Semaphore sem;
//    private final Queue<T> resources =
//       new ConcurrentLinkedQueue<T>();
//    
//    public ResourcePool(int maxResources) {
//      sem = new Semaphore(maxResources, true);
//    }
//    public T getResource(long maxWaitMillis)
//       throws InterruptedException, IOException {
//
//      try {
//      // First, get permission to take or create a resource
//      sem.tryAcquire(maxWaitMillis, TimeUnit.MILLISECONDS);
//
//      // Then, actually take one if available...
//      T res = resources.poll();
//      if (res != null)
//        return res;
//
//      // ...or create one if none available
//        return createResource();
//      } finally {
//        sem.release();
//      }
//    }
//    
//    public void returnResource(T res) {
//      resources.add(res);
//      sem.release();
//    }
//  }
}
