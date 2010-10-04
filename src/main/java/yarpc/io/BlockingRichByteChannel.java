package yarpc.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class BlockingRichByteChannel implements RichByteChannel {
  private final ByteChannel chan;
  private final ExecutorService exec;
  private final InputStream rawIn;
  private final OutputStream rawOut;
  
  public BlockingRichByteChannel(ByteChannel blockingChan, ExecutorService exec) {
    if (blockingChan instanceof SelectableChannel 
        && ! (((SelectableChannel)blockingChan).isBlocking())) {
      throw new RuntimeException("Can't instantiate with nonblocking channel! : " + blockingChan);
    }
    this.chan=blockingChan;
    this.exec=exec;
    rawIn = Channels.newInputStream(this);
    rawOut = Channels.newOutputStream(this);
  }
  
  @Override
  public int read(ByteBuffer dst) throws IOException {
    return chan.read(dst);
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
  public int write(ByteBuffer src) throws IOException {
    return chan.write(src);
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
    return exec.submit(new Callable<Integer>() {
      @Override
      public Integer call() throws Exception {
        return chan.write(src);
      }
    });
  }

  @Override
  public Future<Integer> readFuture(final ByteBuffer dest) {
    return exec.submit(new Callable<Integer>() {
      @Override
      public Integer call() throws Exception {
        return chan.read(dest);
      }
    });
  }

  @Override
  public Future<Long> transferToFile(final FileChannel src, final long position, final long count) {
    final ReadableByteChannel me = this;
    return exec.submit(new Callable<Long>() {
      // filechannel won't do anything tricky because we're not a socket
      @Override
      public Long call() throws Exception {
        return src.transferFrom(me, position, count);
      }
    });
  }

  @Override
  public Future<Long> transferFromFile(final FileChannel dest, final long position, final long count) {
    final WritableByteChannel me = this;
    return exec.submit(new Callable<Long>() {
      // filechannel won't do anything tricky because we're not a socket
      @Override
      public Long call() throws Exception {
        return dest.transferTo(position, count, me);
      }
    });
  }

  /** Actually blocks under the hood, totally ignores timeout, sorry. */
  @Override
  public int tryRead(ByteBuffer dst, int timeout) throws IOException {
    return read(dst);
  }
}
