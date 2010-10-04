package yarpc.io;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.google.common.util.concurrent.AbstractFuture;


/**
 * Provides Future<Integer> and Future<Long> semantics over a nonblocking IO operation,
 * using a set of internal SelectorThreads (allocated round-robin for responsiveness) 
 * and an ExecutorService.
 * 
 * On submission, a RunnableFuture is registered with one of the SelectorThreads and 
 * returned to the caller.  The SelectorThread will register the provided channel and,
 * when it's selector indicates READY for the provided interestOps, will dispatch the 
 * RunnableFuture to an ExecutorService.
 */
public class SelectingExecutor {
  private static final Log LOG = LogFactory.getLog(SelectingExecutor.class);
  private final ExecutorService exec;
  private final SelectThread[] selectThreads;
  private final AtomicInteger roundRobin;
  
  public SelectingExecutor(int numSelectThreads, ExecutorService exec) throws IOException {
    selectThreads = new SelectThread[numSelectThreads];
    for (int i = 0 ; i < numSelectThreads ; i++) {
      selectThreads[i] = new SelectThread();
      selectThreads[i].setDaemon(true);
      selectThreads[i].start();
    }
    this.exec = exec;
    roundRobin = new AtomicInteger(0);
  }
  
  
  
  public <V> Future<V> submit(SelectableChannel selectable, int interestOps, final Callable<V> toCall) {
    // submit with unlimited timeout for normal callables
    return submit(selectable, interestOps, -1, new TimeoutCallable<V>() {
      @Override
      public V call() throws Exception {
        return toCall.call();
      }
      @Override
      public V onTimeout() throws Exception {
        // we'll never timeout so returning null is fine
        return null;
      }
    });
  }
  
  public <V> Future<V> submit(SelectableChannel selectable, int interestOps, int timeout, TimeoutCallable<V> toCall) {
    FutureTask<V> ret = new FutureTask<V>(toCall);
    SelectingFuture todo = new SelectingFuture(selectable, interestOps, timeout, ret);
    selectThreads[roundRobin()].submit(todo);
    return ret;
  }
  
  public int roundRobin() {
    int next = roundRobin.incrementAndGet();
    while (next < 0 || next == Integer.MAX_VALUE) {
      roundRobin.set(0);
      next = roundRobin.incrementAndGet();
    }
    return Math.abs(next % selectThreads.length);
  }
  
  public void close() throws IOException {
    for (SelectThread s : selectThreads)
      s.close();
    exec.shutdown();
  }

  /* Composite used to pass information about an operation to a SelectThread */
  private static class SelectingFuture<T> extends AbstractFuture {
    final SelectableChannel selectable;
    final int interestOps;
    final TimeoutCallable toRun;
    final long timeoutTime;
    
    public SelectingFuture(SelectableChannel selectable, int interestOps, int timeout, TimeoutCallable toRun) {
      this.selectable=selectable;
      this.interestOps=interestOps;
      this.toRun=toRun;
      if (timeout == -1) this.timeoutTime = -1;
      else this.timeoutTime = System.currentTimeMillis() + timeout;
    }

    
  }
  
  private class SelectThread extends Thread {
    private volatile boolean running;
    private final Selector select;
    private final ConcurrentLinkedQueue<SelectingFuture> inbox = new ConcurrentLinkedQueue<SelectingFuture>();
    private final NavigableMap<Long,SelectingFuture> timeouts = new TreeMap<Long,SelectingFuture>();
    
    public SelectThread() throws IOException {
      this.select=Selector.open();
    }
    public void submit(SelectingFuture task) {
      inbox.add(task);
      select.wakeup();
    }
    
    @Override
    public void run() {
      running = true;
      while (running) {
        // scan inbox, register them
        SelectingFuture s;
        while ((s = inbox.poll()) != null) {
          try {
            // register timeout
            if (s.timeoutTime > 0) {
              timeouts.put(s.timeoutTime, s);
            }
            // register for select
            s.selectable.register(select, s.interestOps, s.toRun);
          } catch (ClosedChannelException e) {
            LOG.warn("Exception registering task! " + s + " : " + e);
          }
        }
        // timeout for our select either the time until the shortest timeout is up, or 1ms
        long shortestTimeout =timeouts.firstKey() - System.currentTimeMillis();
        try {
          // if shortestTimeout is in the past, just selectNow
          if (shortestTimeout > 0) select.select(shortestTimeout);
          else select.selectNow();
        } catch (IOException ioe) {
          LOG.fatal("Exception selecting!  Dying.. : " + Utils.stringifyException(ioe));
        }
        // dispatch active selectionkeys to the executor service
        Set<SelectionKey> selected = select.selectedKeys();
        for (SelectionKey key : selected) {
          if (! key.isValid()) {
            key.cancel();
            continue;
          }
          // interest set 0 so we don't launch it again
          key.interestOps(0);
          // take it out of the timeout tree because we don't care anymore
          
          
          // launch
          SelectingFuture task = (SelectingFuture) key.attachment();
          exec.submit(task.toRun);
        }
        // clear selected to avoid busyloops
        selected.clear();
        // now prune all expired tasks
        
        
      }
    }
    
    public void close() {
      running=false;
      select.wakeup();
    }
  }
  
  
}
