// This file is part of OpenTSDB.
// Copyright (C) 2010-2012  The OpenTSDB Authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 2.1 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.tools;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.socket.ServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerBossPool;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioWorkerPool;
import org.jboss.netty.channel.socket.oio.OioServerSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.opentsdb.tools.BuildData;
import net.opentsdb.tools.StreamsConsumer;
import net.opentsdb.core.TSDB;
import net.opentsdb.core.Const;
import net.opentsdb.tsd.PipelineFactory;
import net.opentsdb.tsd.RpcManager;
import net.opentsdb.utils.Config;
import net.opentsdb.utils.Exceptions;
import net.opentsdb.utils.FileSystem;
import net.opentsdb.utils.Threads;

import org.apache.hadoop.conf.Configuration;

import com.mapr.streams.impl.admin.TopicFeedInfo;
import com.mapr.streams.impl.admin.MarlinAdminImpl;
import com.mapr.streams.Admin;
import com.mapr.streams.Streams;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;


/**
 * Main class of the TSD, the Time Series Daemon.
 */
final class TSDMain {

  /** Prints usage and exits with the given retval. */
  static void usage(final ArgP argp, final String errmsg, final int retval) {
    System.err.println(errmsg);
    System.err.println("Usage: tsd --port=PORT"
      + " --staticroot=PATH --cachedir=PATH\n"
      + "Starts the TSD, the Time Series Daemon");
    if (argp != null) {
      System.err.print(argp.usage());
    }
    System.exit(retval);
  }

  private static final short DEFAULT_FLUSH_INTERVAL = 1000;
  
  private static TSDB tsdb = null;
  
  public static void main(String[] args) throws IOException {
    Logger log = LoggerFactory.getLogger(TSDMain.class);
    log.info("Starting.");
    log.info(BuildData.revisionString());
    log.info(BuildData.buildString());
    try {
      System.in.close();  // Release a FD we don't need.
    } catch (Exception e) {
      log.warn("Failed to close stdin", e);
    }

    final ArgP argp = new ArgP();
    CliOptions.addCommon(argp);
    argp.addOption("--port", "NUM", "TCP port to listen on.");
    argp.addOption("--bind", "ADDR", "Address to bind to (default: 0.0.0.0).");
    argp.addOption("--staticroot", "PATH",
                   "Web root from which to serve static files (/s URLs).");
    argp.addOption("--cachedir", "PATH",
                   "Directory under which to cache result of requests.");
    argp.addOption("--worker-threads", "NUM",
                   "Number for async io workers (default: cpu * 2).");
    argp.addOption("--async-io", "true|false",
                   "Use async NIO (default true) or traditional blocking io");
    argp.addOption("--backlog", "NUM",
                   "Size of connection attempt queue (default: 3072 or kernel"
                   + " somaxconn.");
    argp.addOption("--flush-interval", "MSEC",
                   "Maximum time for which a new data point can be buffered"
                   + " (default: " + DEFAULT_FLUSH_INTERVAL + ").");
    CliOptions.addAutoMetricFlag(argp);
    args = CliOptions.parse(argp, args);
    args = null; // free().

    // get a config object
    Config config = CliOptions.getConfig(argp);
    
    // check for the required parameters
    try {
      if (config.getString("tsd.http.staticroot").isEmpty())
        usage(argp, "Missing static root directory", 1);
    } catch(NullPointerException npe) {
      usage(argp, "Missing static root directory", 1);
    }
    try {
      if (config.getString("tsd.http.cachedir").isEmpty())
        usage(argp, "Missing cache directory", 1);
    } catch(NullPointerException npe) {
      usage(argp, "Missing cache directory", 1);
    }
    try {
      if (!config.hasProperty("tsd.network.port"))
        usage(argp, "Missing network port", 1);
      config.getInt("tsd.network.port");
    } catch (NumberFormatException nfe) {
      usage(argp, "Invalid network port setting", 1);
    }

    // validate the cache and staticroot directories
    try {
      FileSystem.checkDirectory(config.getString("tsd.http.staticroot"), 
          !Const.MUST_BE_WRITEABLE, Const.DONT_CREATE);
      FileSystem.checkDirectory(config.getString("tsd.http.cachedir"),
          Const.MUST_BE_WRITEABLE, Const.CREATE_IF_NEEDED);
    } catch (IllegalArgumentException e) {
      usage(argp, e.getMessage(), 3);
    }

    final ServerSocketChannelFactory factory;
    if (config.getBoolean("tsd.network.async_io")) {
      int workers = Runtime.getRuntime().availableProcessors() * 2;
      if (config.hasProperty("tsd.network.worker_threads")) {
        try {
        workers = config.getInt("tsd.network.worker_threads");
        } catch (NumberFormatException nfe) {
          usage(argp, "Invalid worker thread count", 1);
        }
      }
      final Executor executor = Executors.newCachedThreadPool();
      final NioServerBossPool boss_pool = 
          new NioServerBossPool(executor, 1, new Threads.BossThreadNamer());
      final NioWorkerPool worker_pool = new NioWorkerPool(executor, 
          workers, new Threads.WorkerThreadNamer());
      factory = new NioServerSocketChannelFactory(boss_pool, worker_pool);
    } else {
      factory = new OioServerSocketChannelFactory(
          Executors.newCachedThreadPool(), Executors.newCachedThreadPool(), 
          new Threads.PrependThreadNamer());
    }
    
    try {
      tsdb = new TSDB(config);
      tsdb.initializePlugins(true);
      if (config.getBoolean("tsd.storage.hbase.prefetch_meta")) {
        tsdb.preFetchHBaseMeta();
      }
      
      // Make sure we don't even start if we can't find our tables.
      tsdb.checkNecessaryTablesExist().joinUninterruptibly();
      
      // Start the executor service
      ExecutorService streamsConsumerExecutor = Executors.newCachedThreadPool();
      registerShutdownHook(streamsConsumerExecutor);
      final ServerBootstrap server = new ServerBootstrap(factory);
      
      // This manager is capable of lazy init, but we force an init
      // here to fail fast.
      final RpcManager manager = RpcManager.instance(tsdb);

      server.setPipelineFactory(new PipelineFactory(tsdb, manager));
      if (config.hasProperty("tsd.network.backlog")) {
        server.setOption("backlog", config.getInt("tsd.network.backlog")); 
      }
      server.setOption("child.tcpNoDelay", 
          config.getBoolean("tsd.network.tcp_no_delay"));
      server.setOption("child.keepAlive", 
          config.getBoolean("tsd.network.keep_alive"));
      server.setOption("reuseAddress", 
          config.getBoolean("tsd.network.reuse_address"));

      // null is interpreted as the wildcard address.
      InetAddress bindAddress = null;
      if (config.hasProperty("tsd.network.bind")) {
        bindAddress = InetAddress.getByName(config.getString("tsd.network.bind"));
      }

      // we validated the network port config earlier
      final InetSocketAddress addr = new InetSocketAddress(bindAddress,
          config.getInt("tsd.network.port"));
      server.bind(addr);
      log.info("Ready to serve on " + addr);

      boolean useStreams = config.getBoolean("tsd.default.usestreams");
      if (useStreams) {
        // Get the default stream name from config
        String streamName = config.getString("tsd.default.stream");
        if (streamName == null || streamName.isEmpty()) streamName = "/var/mapr/mapr.monitoring/spyglass";
        String consumerGroup = config.getString("tsd.default.consumergroup");
        if (consumerGroup == null || consumerGroup.isEmpty()) consumerGroup = "metrics";
        startConsumers(tsdb, streamName, consumerGroup, streamsConsumerExecutor);
      }

      log.info("Starting.");
    } catch (Throwable e) {
      factory.releaseExternalResources();
      try {
        if (tsdb != null)
          tsdb.shutdown().joinUninterruptibly();
      } catch (Exception e2) {
        log.error("Failed to shutdown HBase client", e2);
      }
      throw new RuntimeException("Initialization failed", e);
    }
    // The server is now running in separate threads, we can exit main.
  }

  private static void startConsumers(TSDB tsdb, String streamName, String consumerGroup, ExecutorService executor) {
    try {

      final Configuration conf = new Configuration();
      StreamsConsumer consumer = new StreamsConsumer(tsdb, streamName, consumerGroup);
      executor.submit(consumer); // TODO - Add reconnect logic and a way to monitor the consumers
    } catch (Exception e) {
      LoggerFactory.getLogger(TSDMain.class).error("Failed to create consumer with error: "+e.getMessage());
    }
  }

  private static void registerShutdownHook(ExecutorService executor) {
    final class TSDBShutdown extends Thread {
      ExecutorService executor;
      public TSDBShutdown(ExecutorService executor) {
        super("TSDBShutdown");
        this.executor = executor;
      }
      public void run() {
        try {
          executor.shutdown(); // Disable new tasks from being submitted
          try {
            // Wait a while for existing tasks to terminate
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
              executor.shutdownNow(); // Cancel currently executing tasks
              // Wait a while for tasks to respond to being cancelled
              if (!executor.awaitTermination(30, TimeUnit.SECONDS))
                LoggerFactory.getLogger(TSDBShutdown.class).error("Pool did not terminate");
            }
          } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            executor.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
          }
          if (RpcManager.isInitialized()) {
            // Check that its actually been initialized.  We don't want to
            // create a new instance only to shutdown!
            RpcManager.instance(tsdb).shutdown().join();
          }
          if (tsdb != null) {
            tsdb.shutdown().join();
          }
        } catch (Exception e) {
          LoggerFactory.getLogger(TSDBShutdown.class)
            .error("Uncaught exception during shutdown", e);
        }
      }
    }
    Runtime.getRuntime().addShutdownHook(new TSDBShutdown(executor));
  }
}
