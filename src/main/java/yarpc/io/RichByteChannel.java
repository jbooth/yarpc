package yarpc.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.FileChannel;
import java.util.concurrent.Future;

/**
 * Interface supplying a few additional methods on top of ReadableByteChannel
 * and WritableByteChannel.  ReadableByteChannel and WritableByteChannel 
 * methods are blocking.  
 */
public interface RichByteChannel extends ByteChannel {
  
  /** Unbuffered InputStream reading from this channel in blocking fashion. */
  public InputStream rawIn();
  
  /** Unbuffered OutputStream writing to this channel in blocking fashion. */
  public OutputStream rawOut();

  /** Attempts to read from the underlying channel, returning 0 if not readable before timeout. */
  public int tryRead(ByteBuffer dst, int timeout) throws IOException;
  
  /** Attempts to read from the underlying channel.  
   * If not immediately readable, blocks until readable and then executes. 
   * Returns number read.  */
  @Override
  public int read(ByteBuffer dst) throws IOException;
  
  /** Attempts to write to the underlying channel.  
   * If not immediately writable, blocks until writable and then executes. 
   * Returns number written. */
  @Override
  public int write(ByteBuffer src) throws IOException;
  
  /** Returns a Future&lt;Integer&gt; reflecting the status of a separately executing write. */
  public Future<Integer> writeFuture(ByteBuffer src);
  
  /** Returns a Future&lt;Integer&gt; reflecting the status of a separately executing read.  */
  public Future<Integer> readFuture(ByteBuffer dest);
  
  /** Executes a transferFrom from our channel to dest after we're confirmed as readable. */
  public Future<Long> transferToFile(FileChannel dest, long position, long count);
  
  /** Executes a transferTo from src to our channel after we're confirmed as writable. */
  public Future<Long> transferFromFile(FileChannel src, long position, long count);
}
