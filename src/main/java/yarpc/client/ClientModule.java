package yarpc.client;

import java.io.IOException;

import yarpc.io.RichByteChannel;

public interface ClientModule {
  
  public Object handleClient(RichByteChannel server) throws IOException;
}
