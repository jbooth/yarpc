package yarpc.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Implementation of RichByteChannel for non-blocking SocketChannels.
 * 
 */
public class SocketRichByteChannel implements RichByteChannel {
  private final SocketChannel chan;
  private final SelectingExecutor exec;
  private final InputStream rawIn;
  private final OutputStream rawOut;

  /**
   * We need a SelectingExecutor, preferably shared with other Channels, to
   * operate.
   * 
   * Constructor is currently bound to SocketChannel for convenience of
   * implementation instead of having to check and cast all appropriate
   * interfaces.
   * 
   * @param chan
   * @param exec
   * @throws IllegalArgumentException
   *           if chan is in blocking mode
   */
  public SocketRichByteChannel(SocketChannel chan, SelectingExecutor exec) {
    if (chan.isBlocking())
      throw new IllegalArgumentException(
          "SocketRichByteChannel only works with nonblocking sockets!");
    this.chan = chan;
    this.exec = exec;
    this.rawIn = Channels.newInputStream(this);
    this.rawOut = Channels.newOutputStream(this);
  }

  @Override
  public int tryRead(ByteBuffer dst, int timeout) throws IOException {
    return chan.read(dst);
  }

  @Override
  public int read(final ByteBuffer dst) throws IOException {
    // try to read from calling thread first
    int attempt = chan.read(dst);
    if (attempt > 0)
      return attempt;
    else {
      try {
        // dispatch and block
        return readFuture(dst).get();
      } catch (ExecutionException ee) {
        if (ee.getCause() instanceof IOException)
          throw (IOException)ee.getCause();
        else
          throw new RuntimeException(ee.getCause());
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public boolean isOpen() {
    return chan.isOpen();
  }

  @Override
  public void close() throws IOException {
    chan.close();
  }

  @Override
  public int write(final ByteBuffer src) throws IOException {
    // try to write from calling thread first
    int attempt = chan.write(src);
    if (attempt > 0)
      return attempt;
    else {
      try {
        // dispatch and block
        return writeFuture(src).get();
      } catch (ExecutionException ee) {
        if (ee.getCause() instanceof IOException)
          throw (IOException) ee.getCause();
        else
          throw new RuntimeException(ee.getCause());
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public InputStream rawIn() {
    return rawIn;
  }

  @Override
  public OutputStream rawOut() {
    return rawOut;
  }

  @Override
  public Future<Integer> writeFuture(final ByteBuffer src) {
    return exec.submit(chan, SelectionKey.OP_WRITE, new Callable<Integer>() {
      @Override
      public Integer call() throws Exception {
        return chan.write(src);
      }
    });
  }

  @Override
  public Future<Integer> readFuture(final ByteBuffer dest) {
    return exec.submit(chan, SelectionKey.OP_READ, new Callable<Integer>() {
      @Override
      public Integer call() throws Exception {
        return chan.read(dest);
      }
    });
  }

  @Override
  public Future<Long> transferToFile(final FileChannel src,
      final long position, final long count) {
    return exec.submit(chan, SelectionKey.OP_READ, new Callable<Long>() {
      @Override
      public Long call() throws Exception {
        return src.transferTo(position, count, chan);
      }
    });
  }

  @Override
  public Future<Long> transferFromFile(final FileChannel dest,
      final long position, final long count) {
    return exec.submit(chan, SelectionKey.OP_WRITE, new Callable<Long>() {
      @Override
      public Long call() throws Exception {
        return dest.transferFrom(chan, position, count);
      }
    });
  }

}
