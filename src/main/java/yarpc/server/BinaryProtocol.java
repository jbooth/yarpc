package yarpc.server;

import java.io.IOException;
import java.nio.ByteBuffer;

import yarpc.io.RichByteChannel;

import com.google.common.primitives.Ints;

/**
 * A few very simple methods implemented around RichByteChannel to send system messages between
 * BinaryServer and BinaryClient
 *
 * Implemented as raw protocols using DataInput/DataOutput.
 */
public class BinaryProtocol {
  
  
  /** Sends HELO - all we send is an int representing keepalive duration */
  public static void sndHELO(RichByteChannel server, int keepalive) throws IOException {
    // quick buffer and flush
    ByteBuffer intBuff = ByteBuffer.wrap(Ints.toByteArray(keepalive));
    while (intBuff.hasRemaining()) { server.write(intBuff); }
  }
  
  /** rcv HELO, return keepalive */
  public static int rcvHELO(RichByteChannel client) throws IOException {
    ByteBuffer intBuff = ByteBuffer.allocate(4);
    while (intBuff.hasRemaining()) { client.read(intBuff); }
    intBuff.flip();
    return intBuff.getInt(0);
  }
  
  /** Sends a request String, blocking.  Protocol is int for size, then bytes in UTF-8.  */
  public static void sndREQUEST(RichByteChannel server, String req) throws IOException {
    byte[] utf8 = req.getBytes("UTF-8");
    ByteBuffer request = ByteBuffer.allocate(utf8.length + 4);
    request.put(Ints.toByteArray(utf8.length));
    request.put(utf8);
    while (request.hasRemaining()) server.write(request);
  }
  
  /** Attempts to read a request String.  If not immediately readable, returns null. */
  public static String tryRcvREQUEST(RichByteChannel client) throws IOException {
    ByteBuffer intBuff = ByteBuffer.allocate(4);
    int r = client.tryRead(intBuff);
    if (r == 0) return null;
    // make sure we didn't read partial int somehow
    while (intBuff.hasRemaining()) client.read(intBuff);
    int size = Ints.fromByteArray(intBuff.array());
    ByteBuffer ret = ByteBuffer.wrap(new byte[size]);
    while (ret.hasRemaining()) client.read(ret);
    return new String(ret.array(), "UTF-8");
  }
  
  private static final byte ACK = 1;
  
  public static void sendAck(RichByteChannel client) throws IOException {
    ByteBuffer ack = ByteBuffer.allocate(1);
    ack.put(ACK);
    ack.flip();
    while (ack.hasRemaining()) client.write(ack);
  }
  
  public static boolean rcvAck(RichByteChannel server, int timeout) throws IOException {
    ByteBuffer ack = ByteBuffer.allocate(1);
    
  }
  
  
  
  
  
}
