package yarpc.avro;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

import org.apache.avro.Protocol;
import org.apache.avro.ipc.Transceiver;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Copy of SocketChannelTransceiver built around ByteChannel interface
 */
class ByteChannelTransceiver extends Transceiver {
  private static final Log LOG
  = LogFactory.getLog(ByteChannelTransceiver.class);

private final ByteChannel channel;
private ByteBuffer header = ByteBuffer.allocate(4);

private Protocol remote;

ByteChannelTransceiver(ByteChannel channel) throws IOException {
  this.channel = channel;
  LOG.info("open to "+getRemoteName());
}

public String getRemoteName() {
  if (channel instanceof SocketChannel) {
    return ((SocketChannel)channel).socket().getRemoteSocketAddress().toString();    
  } else {
    return channel.toString();
  }
}

public synchronized List<ByteBuffer> readBuffers() throws IOException {
  List<ByteBuffer> buffers = new ArrayList<ByteBuffer>();
  while (true) {
    header.clear();
    while (header.hasRemaining()) {
      channel.read(header);
    }
    header.flip();
    int length = header.getInt();
    if (length == 0) {                       // end of buffers
      return buffers;
    }
    ByteBuffer buffer = ByteBuffer.allocate(length);
    while (buffer.hasRemaining()) {
      channel.read(buffer);
    }
    buffer.flip();
    buffers.add(buffer);
  }
}

public synchronized void writeBuffers(List<ByteBuffer> buffers)
  throws IOException {
  if (buffers == null) return;                  // no data to write
  for (ByteBuffer buffer : buffers) {
    if (buffer.limit() == 0) continue;
    writeLength(buffer.limit());                // length-prefix
    channel.write(buffer);
  }
  writeLength(0);                               // null-terminate
}

private void writeLength(int length) throws IOException {
  header.clear();
  header.putInt(length);
  header.flip();
  channel.write(header);
}

// for newer versions of avro once they're out
//@Override public boolean isConnected() { return remote != null; }
//
//@Override public void setRemote(Protocol remote) {
//  this.remote = remote;
//}
//
//@Override public Protocol getRemote() {
//  return remote;
//}

@Override public void close() throws IOException {
  if (channel.isOpen()) {
    LOG.info("closing to "+getRemoteName());
    channel.close();
  }
}


}
