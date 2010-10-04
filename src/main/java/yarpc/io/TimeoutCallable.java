package yarpc.io;

import java.util.concurrent.Callable;

public interface TimeoutCallable<T>  extends Callable<T> {
  public T onTimeout() throws Exception;
}
