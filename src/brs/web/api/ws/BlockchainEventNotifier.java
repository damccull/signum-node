package brs.web.api.ws;

import brs.*;
import brs.props.Props;
import brs.web.api.ws.common.Debouncer2;
import brs.web.api.ws.common.SimpleScheduler;
import brs.web.api.ws.emitter.data.ConnectedEventData;
import brs.web.api.ws.common.Debouncer;
import brs.web.api.ws.emitter.*;
import brs.web.server.WebServerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.*;

public class BlockchainEventNotifier {

  private final static int SHUTDOWN_TIMEOUT_SECS = 5;
  private final static int HEARTBEAT_INTERVAL_SECS = 10; // make this configurable
  private final static int BLOCK_PUSHED_DEBOUNCE_SECS = 1;
  private final static int IO_THREAD_COUNT = 10;
  private static BlockchainEventNotifier instance;
  private final ExecutorService notifyExecutor;
//  private final Debouncer blockPushedDebouncer;
  private final Logger logger = LoggerFactory.getLogger(BlockchainEventNotifier.class);
  private final WebServerContext context;
  private final ConcurrentHashMap<String, WebSocketConnection> connections = new ConcurrentHashMap<>();
  private final Debouncer2 blockPushedDebouncer2;
  private SimpleScheduler heartbeat;

  public static BlockchainEventNotifier getInstance(WebServerContext context) {
    if (BlockchainEventNotifier.instance == null) {
      BlockchainEventNotifier.instance = new BlockchainEventNotifier(context);
    }
    return BlockchainEventNotifier.instance;
  }

  private BlockchainEventNotifier(WebServerContext context) {
    this.context = context;
    this.notifyExecutor = Executors.newFixedThreadPool(IO_THREAD_COUNT);
    this.context.getBlockchainProcessor().addListener(this::onBlockGeneratedEvent, BlockchainProcessor.Event.BLOCK_GENERATED);
    this.context.getBlockchainProcessor().addListener(this::onBlockPushedEvent, BlockchainProcessor.Event.BLOCK_PUSHED);
    this.context.getTransactionProcessor().addListener(this::onPendingTransactionEvent, TransactionProcessor.Event.ADDED_UNCONFIRMED_TRANSACTIONS);
//    this.blockPushedDebouncer = new Debouncer(BLOCK_PUSHED_DEBOUNCE_SECS * 1000);
    this.blockPushedDebouncer2 = new Debouncer2();
    initializeHeartBeat();
  }


  private void withActiveConnectionsOnly(Runnable fn) {
    if (!this.connections.isEmpty()) {
      fn.run();
    }
  }

  public void addConnection(WebSocketConnection connection) {
    connections.put(connection.getId(), connection);

    this.notifyExecutor.submit(() -> {
      ConnectedEventData data = new ConnectedEventData();
      data.version = Burst.VERSION.toString();
      data.networkName = context.getPropertyService().getString(Props.NETWORK_NAME);
      data.globalHeight = context.getBlockchainProcessor().getLastBlockchainFeederHeight();
      data.localHeight = context.getBlockchain().getHeight();
      new ConnectedEventEmitter(connection).emit(data);
    });
  }

  private void initializeHeartBeat() {
    int intervalSecs = context.getPropertyService().getInt(Props.API_WEBSOCKET_HEARTBEAT_INTERVAL);
    int blockTimeSecs = context.getPropertyService().getInt(Props.BLOCK_TIME);
    if (intervalSecs <= 0) {
      throw new IllegalStateException("Heartbeat interval must be > 0");
    }
    if (intervalSecs >= blockTimeSecs) {
      intervalSecs = blockTimeSecs / 2;
      logger.warn("Heartbeat interval must be less than block time ({} seconds) - set to {} seconds", blockTimeSecs, intervalSecs);
    }
    heartbeat = new SimpleScheduler(intervalSecs, SHUTDOWN_TIMEOUT_SECS);
    heartbeat.start(() ->
      withActiveConnectionsOnly(() -> {
        notifyExecutor.submit(() -> {
          connections.values().forEach(connection -> new HeartBeatEventEmitter(connection).emit());
        });
      })
    );
  }


  public void removeConnection(WebSocketConnection connection) {
    connections.remove(connection.getId());
  }

  public void onBlockGeneratedEvent(Block block) {
    withActiveConnectionsOnly(() -> {
      notifyExecutor.submit(() -> {
        connections.values().forEach(connection -> new BlockGeneratedEventEmitter(connection).emit(block));
      });
    });
  }

  public void onBlockPushedEvent(Block block) {
    withActiveConnectionsOnly(() -> {
      blockPushedDebouncer2.debounce(
        BlockchainEventNotifier.class,
        () -> {
          notifyExecutor.submit(() -> {
            int currentHeight = context.getBlockchainProcessor().getLastBlockchainFeederHeight();
            connections.values().forEach(connection -> new BlockPushedEventEmitter(connection, currentHeight).emit(block));
          });
        },
        BLOCK_PUSHED_DEBOUNCE_SECS, TimeUnit.SECONDS);
    });
  }

  private void onPendingTransactionEvent(List<? extends Transaction> transactions) {
    withActiveConnectionsOnly(() -> {
      notifyExecutor.submit(() -> {
        connections.values().forEach(connection -> new PendingTransactionsAddedEventEmitter(connection).emit(transactions));
      });
    });
  }

  private void shutdownNotifyExecutor() {
    if (!notifyExecutor.isTerminated()) {
      logger.info("Closing {} websocket connection(s)...", connections.size());
      notifyExecutor.submit(() -> {
        connections.values().forEach(WebSocketConnection::close);
      });
      notifyExecutor.shutdown();
      try {
        if (!notifyExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECS, TimeUnit.SECONDS)) {
          notifyExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        logger.warn("Some threads didn't terminate, forcing shutdown");
        notifyExecutor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  public void shutdown() {
    try {
      if (heartbeat != null) {
        heartbeat.shutdown();
      }
      blockPushedDebouncer2.shutdown(SHUTDOWN_TIMEOUT_SECS);
      shutdownNotifyExecutor();
    } catch (Exception e) {
      logger.warn("Graceful WebSocket shutdown not successful: {}", e.getMessage());
    }
  }

}
