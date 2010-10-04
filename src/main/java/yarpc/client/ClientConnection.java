package yarpc.client;

import java.io.IOException;
import java.net.InetAddress;

import yarpc.io.RichByteChannel;

class ClientConnection {
  private final RichByteChannel server;
  
  public ClientConnection(InetAddress server, int keepAlive) throws IOException {
    throw new IOException("hi");
  }
}
