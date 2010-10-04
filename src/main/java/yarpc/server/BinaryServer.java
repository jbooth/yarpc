package yarpc.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.avro.ipc.Server;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import yarpc.io.RichByteChannel;
import yarpc.io.SelectingExecutor;
import yarpc.io.SocketRichByteChannel;
import yarpc.io.Utils;


/** 
 * High Performance Java Binary RPC Server.  Keeps connections alive for pooling on client side.
 * Custom and very small internal protocol for keepalive-related maintenance.
 *
 * 
 */
public class BinaryServer implements Server {
  public static final int SO_TIMEOUT=600000; // 10 minutes
  public static Log LOG = LogFactory.getLog(BinaryServer.class);
  private final Acceptor acceptor;
  private final ExecutorService mainExec;
  private final SelectingExecutor mainSelect;
  private final Relistener relistener;
  
  public BinaryServer(Map<String, ServerModule> modules, SocketAddress endpoint) throws IOException {
    // min 3, max 20k threads, threads expire after idle for 60S
    this.mainExec = new ThreadPoolExecutor(3, 20000,
        60L, TimeUnit.SECONDS,
        new SynchronousQueue<Runnable>());
    this.mainSelect = new SelectingExecutor(4, mainExec);
    relistener = new Relistener(modules);
    acceptor = new Acceptor(endpoint);
  }
  
  @Override
  public int getPort() {
    return acceptor.getPort();
  }

  @Override
  public void close() {
    acceptor.close();
    try {
      relistener.close();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void join() throws InterruptedException {
    acceptor.join();
    relistener.join();
  }

  public void start() {
    acceptor.start(); 
  }
  
  private class Acceptor extends Thread implements Closeable {
    private final SocketAddress endpoint;
    private final ServerSocketChannel server;
    private volatile boolean running=false;
    
    public Acceptor(SocketAddress endpoint) throws IOException {
      this.endpoint=endpoint;
      this.server = ServerSocketChannel.open();
      // tell clients to immediately scale to 128k
      server.socket().setReceiveBufferSize(128*1024);
      server.configureBlocking(true);
      server.socket().bind(endpoint);
    }
    
    @Override
    public void run() {
      running = true;
      try {
        // dispatch sockets to executor server immediately upon connect
        while (running) {
          SocketChannel client = server.accept();
          mainExec.submit(new DoAccept(client));
        }
      } catch (Throwable e) {
        LOG.fatal(Utils.stringifyException(e));
        running = false;
      }
      server.close();
    }
    
    public void close() {
      running = false;
      this.interrupt();
    }
    
    public int getPort() {
      return server.socket().getLocalPort();
    }
  }
  
  /** Sets up a connection, processes */
  private class DoAccept implements Runnable {
    private final SocketChannel client;
    
    public DoAccept(SocketChannel client) {
      this.client = client;
    }

    @Override
    public void run() {
      client.configureBlocking(false);
      client.socket().setSoTimeout(SO_TIMEOUT);
      client.socket().setKeepAlive(true);
      client.socket().setTcpNoDelay(true);
      client.socket().setSendBufferSize(128*1024);
      Connection conn = new Connection(client, mainSelect);
      relistener.register(conn);
    }
  }
  
  /** Holds onto the SocketChannel, RichByteChannel and keepAlive state */
  private static class Connection {
    private final SocketChannel rawClient;
    private final int keepAlive;
    private final String remoteName;
    private volatile long lastContact = System.currentTimeMillis();
    final RichByteChannel client;
    
    /** Reads client-supplied keepAlive as part of HELO */
    public Connection(SocketChannel rawClient, SelectingExecutor select) throws IOException {
      this.rawClient=rawClient;
      this.client= new SocketRichByteChannel(this.rawClient, select);
      remoteName = rawClient.socket().getRemoteSocketAddress().toString();
      keepAlive = BinaryProtocol.rcvHELO(this.client);
    }
    
    void configureBlocking(boolean blocking) throws IOException {
      rawClient.configureBlocking(blocking);
    }
    
    void noteContact() { this.lastContact = System.currentTimeMillis(); }
    
    /** True if our last contacted time is longer ago than keepAlive */
    boolean shouldKeepAlive() {
      return ( (System.currentTimeMillis() - lastContact) >= keepAlive );
    }
    
    @Override public String toString() { return remoteName; }
    
    /** Closes channel if open.  Swallows and logs exception. */
    public void close() {
      try {
        if (rawClient.isOpen()) rawClient.close();        
      } catch (IOException ioe) {
        LOG.warn("Error closing client + " + this + ioe);
      }
    }
  }
  
  private static class Relistener extends Thread implements Closeable {
    private final Selector select;
    private final ExecutorService threads;
    private final ConcurrentLinkedQueue<Connection> inbox;
    private final Map<String, ServerModule> modules;
    private volatile boolean running=false;
    
    public Relistener(Map<String, ServerModule> modules) throws IOException {
      this.select=Selector.open();
      this.threads=Executors.newCachedThreadPool();
      this.inbox = new ConcurrentLinkedQueue<Connection>();
      this.modules= Collections.unmodifiableMap(modules);
    }
    
    @Override
    public void run() {
      running=true;
      while(running) {
        Connection c;
        // scan inbox and add to selector
        while ( (c = inbox.poll()) != null) {
          if (c != null) {
            try {
              c.configureBlocking(false);
              c.rawClient.register(select, SelectionKey.OP_READ, c);
            } catch (IOException ioe) {
              LOG.warn("Exception registering conn " + c + ", closing and proceeding.. : " + Utils.stringifyException(ioe));
              c.close();
            }
          }
        }
        // clean dead connections
        for (SelectionKey s : select.keys()) {
          Connection conn = (Connection) s.attachment();
          
        }
        // select
        try {
          select.select();          
        } catch (IOException ioe) {
          LOG.fatal("Exception on select()!" + Utils.stringifyException(ioe));
          LOG.fatal("Dying");
          running = false;
          return;
        }
        // attempt to read and dispatch
        // if no good, requeue
        Set<SelectionKey> selected = select.selectedKeys();
        for (SelectionKey s : selected) {
          selected.remove(s);
          final Connection conn = (Connection) s.attachment();
          try {
            String request = conn.tryReadModuleName();
            // if not immediately readable for some reason, just put it back in the queue
            // we will re-register it for select() next time through the loop
            if (request == null) {
              inbox.add(conn);
              continue;
            }
            final ServerModule module = modules.get(request);
            threads.submit(new Runnable(){
              @Override
              public void run() {
                try {
                  module.handle(conn.client);
                  conn.noteContact();
                  register(conn);
                } catch (Throwable t) {
                  LOG.warn("Got exception handling " + conn + ", closing.. : " + Utils.stringifyException(t));
                  conn.close();
                } 
              }
            }); 
          } catch (IOException ioe) {
            LOG.warn("Exception handling conn " + c + ", closing and proceeding.. :" + Utils.stringifyException(ioe));
            conn.close();
          }
        }
      }
    }
    
    public void register(Connection conn) {
      inbox.add(conn);
      select.wakeup();
    }

    @Override
    public void close() throws IOException {
      running = false;
      select.wakeup();
    }
  }
  
  /** Sets up connection, tries to execute and immediate request and then requeues for relistening */
  private class DoAccept implements Runnable {

    @Override
    public void run() {
      // TODO Auto-generated method stub
      
    }
    
  }
  
  /** If there's a request on the wire, execute and then re-listen.  Otherwise re-listen immediately. */
  private class TryExecOrReListen implements Runnable {
    private final Connection conn;
    private final ExecutorService exec;
    private final Map<String,BinaryModule> modules;
    @Override
    public void run() {
      // TODO Auto-generated method stub
      
    }
    
    
    
  }
}
