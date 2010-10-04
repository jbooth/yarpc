package yarpc.server;

import yarpc.io.RichByteChannel;


/**
 * Base interface for a module able to handle requests.  Modules are bound to an interface name
 * in the main server.
 */
public interface ServerModule {
  
  public void handle(RichByteChannel client) throws Exception;
}
