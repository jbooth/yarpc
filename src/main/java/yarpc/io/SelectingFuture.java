package yarpc.io;

import java.nio.channels.SelectableChannel;
import java.util.concurrent.RunnableFuture;

import com.google.common.util.concurrent.AbstractFuture;

public class SelectingFuture<T> extends AbstractFuture<T> {
  final SelectableChannel selectable;
  final int interestOps;
  final RunnableFuture toRun;
  final long timeoutTime;
  boolean dispatched=false;
  
  
}
