package yarpc.avro;

import org.apache.avro.Protocol;

import yarpc.io.RichByteChannel;
import yarpc.server.ServerModule;

public class AvroModule implements ServerModule {
  
  public AvroModule(Protocol iface, Object impl) {
    
  }
  
  
  @Override
  public void handle(RichByteChannel client) throws Exception {
    
  }

}
