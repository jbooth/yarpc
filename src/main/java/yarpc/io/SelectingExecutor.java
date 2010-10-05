package yarpc.io;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.google.common.base.Supplier;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
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
    
    SelectingFuture todo = new SelectingFuture(selectable, interestOps, timeout, toCall);
    selectThreads[roundRobin()].submit(todo);
    return todo;
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
  private static class SelectingFuture<T> extends AbstractFuture<T> implements RunnableFuture<T> {
    final SelectableChannel selectable;
    final int interestOps;
    final TimeoutCallable<T> toRun;
    final long timeoutTime;
    
    public SelectingFuture(SelectableChannel selectable, int interestOps, int timeout, TimeoutCallable<T> toRun) {
      this.selectable=selectable;
      this.interestOps=interestOps;
      this.toRun=toRun;
      if (timeout <= 0) this.timeoutTime = -1;
      else this.timeoutTime = System.currentTimeMillis() + timeout;
    }
    
    // call this method on normal dispatch
    @Override
    public void run() {
      try {
        set(toRun.call());
      } catch (Throwable t) {
        setException(t);
      }
    }
    
    // call this method if we timed out before running
    public void timeout() {
      try {
    	  set(toRun.onTimeout());
      } catch (Throwable t) {
    	 setException(t);
      }
    }
  }
  
  private class SelectThread extends Thread {
    private volatile boolean running;
    private final Selector select;
    private final ConcurrentLinkedQueue<SelectingFuture> inbox = new ConcurrentLinkedQueue<SelectingFuture>();
    private final NavigableMap<Long,Collection<SelectingFuture>> timeoutKeys = new TreeMap<Long,Collection<SelectingFuture>>();
    private final Multimap<Long, SelectingFuture> timeoutsMulti = Multimaps.newListMultimap(timeoutKeys, new Supplier<List<SelectingFuture>>() {
      @Override
      public List<SelectingFuture> get() {
        return new ArrayList<SelectingFuture>();
      }
    });
    
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
      // main loop has 4 phases
      // 1)  
      
      while (running) {
        // 1) scan inbox, register them
        SelectingFuture s;
        while ((s = inbox.poll()) != null) {
          try {
            // register timeout in multimap
            if (s.timeoutTime > 0) {
              timeoutsMulti.put(s.timeoutTime, s);
            }
            // register for select
            s.selectable.register(select, s.interestOps, s.toRun);
          } catch (ClosedChannelException e) {
            LOG.warn("Exception registering task! " + s + " : " + e);
          }
        }
        // 2) select
        // timeout for our select either the time until the shortest timeout is up, or 1ms
        long shortestTimeout =timeoutKeys.firstKey() - System.currentTimeMillis();
        try {
          // if shortestTimeout is in the past, just selectNow
          if (shortestTimeout > 0) select.select(shortestTimeout);
          else select.selectNow();
        } catch (IOException ioe) {
          LOG.fatal("Exception selecting!  Dying.. : " + Utils.stringifyException(ioe));
        }
        // 3) dispatch
        // dispatch active selectionkeys to the executor service
        Set<SelectionKey> selected = select.selectedKeys();
        for (SelectionKey key : selected) {
          if (! key.isValid()) {
            key.cancel();
            continue;
          }
          // interest set 0 so we don't launch it again
          key.interestOps(0);
          
          SelectingFuture task = (SelectingFuture) key.attachment();
          // take it out of the timeout tree because we don't care anymore
          timeoutsMulti.remove(task.timeoutTime, task);
          // launch
          exec.submit(task.toRun);
        }
        // clear selected to avoid busyloops
        selected.clear();
        // 4) timeout
        // now prune all expired tasks
        Set<Long> timedOutKeys = timeoutKeys.headMap(System.currentTimeMillis(), true).keySet();
        for (long timedOut : timedOutKeys) {
          // remove all Futures that timeout at this millis
          Collection<SelectingFuture> futuresWithKey = timeoutsMulti.removeAll(timedOut);
          // submit timeout return
          for (final SelectingFuture f : futuresWithKey) {
            exec.submit(new Runnable() {
              @Override
              public void run() {
                f.timeout();
              }
            });
          }
        }
      }
    }
    
    public void close() {
      running=false;
      select.wakeup();
    }
  }
  
  
}
