package com.verisign.storm.tools.sbod.bolts

import java.util

import backtype.storm.Config
import backtype.storm.task.TopologyContext
import backtype.storm.topology.base.BaseBasicBolt
import backtype.storm.topology.{BasicOutputCollector, OutputFieldsDeclarer}
import backtype.storm.tuple.{Fields, Tuple}
import com.google.common.base.Throwables
import com.verisign.storm.tools.sbod.logging.TransientLazyLogging
import org.apache.curator.framework.recipes.cache.NodeCache
import org.apache.curator.framework.{CuratorFrameworkFactory, CuratorFramework}
import org.apache.curator.retry.ExponentialBackoffRetry
import scala.collection.JavaConverters._

import scala.util.{Failure, Success, Try}

/**
 * Fails by design whenever an input tuple is received, assuming the bolt's "kill switch" is enabled.
 *
 * This bolt watches a ZNode in ZooKeeper to determine whether its kill switch is enabled or not:
 *
 * - If the znode path (default: "/bolt-of-death-stop") does not exist, then the bolt's kill switch is enabled.
 *   In this case the bolt will throw a [[RuntimeException]] whenever it receives an input tuple.
 * - If the znode path exists in the ZooKeeper ensemble used by Storm, then the kill switch is disabled.  In this case
 *   the bolt will not throw [[RuntimeException]] intentionally and simply ack every input tuple instead.
 *
 * @param killSwitchZnode the watched ZNode in ZooKeeper
 */
class BoltOfDeath(killSwitchZnode: String = "/bolt-of-death-stop") extends BaseBasicBolt with TransientLazyLogging {

  private val KillSwitchEnabledDefault = true
  private val BuildInitialCache = true

  private var curator: Option[CuratorFramework] = None
  private var nodeCache: Option[NodeCache] = None

  override def prepare(stormConf: util.Map[_, _], context: TopologyContext): Unit = {
    logger.info("Creating Curator instance...")
    curator = createCurator(stormConf) match {
      case Success(c) => Option(c)
      case Failure(e) =>
        logger.error("Failed to create a Curator instance: " + Throwables.getStackTraceAsString(e))
        None
    }
    for { c <- curator} {
      logger.info("Connecting to ZooKeeper via Curator...")
      c.start()
      logger.info(s"Creating NodeCache for $killSwitchZnode...")
      nodeCache = createNodeCache(c, killSwitchZnode) match {
        case Success(n) => Option(n)
        case Failure(e) =>
          logger.error(s"Failed to create a NodeCache for $killSwitchZnode")
          None
      }
      for { n <- nodeCache } { n.start(BuildInitialCache) }
    }
  }

  private def createCurator(stormConf: util.Map[_, _]): Try[CuratorFramework] = Try {
    val connectString = {
      val zkServers = Option(stormConf.get(Config.STORM_ZOOKEEPER_SERVERS)).
          getOrElse(clojure.lang.PersistentVector.create("localhost")).asInstanceOf[clojure.lang.PersistentVector]
      val zkPort = getConfig(stormConf, Config.STORM_ZOOKEEPER_PORT, 2181).toInt
      zkServers.iterator.asScala.map { s => s + ":" + zkPort} mkString ","
    }
    val sessionTimeoutMs = getConfig(stormConf, Config.STORM_ZOOKEEPER_SESSION_TIMEOUT, 20000).toInt
    val connectionTimeoutMs = getConfig(stormConf, Config.STORM_ZOOKEEPER_CONNECTION_TIMEOUT, 15000).toInt
    val retryPolicy = {
      val baseSleepTimeMs = getConfig(stormConf, Config.STORM_ZOOKEEPER_RETRY_INTERVAL, 1000).toInt
      val maxRetries = getConfig(stormConf, Config.STORM_ZOOKEEPER_RETRY_TIMES, 5).toInt
      val maxSleepMs = getConfig(stormConf, Config.STORM_ZOOKEEPER_RETRY_INTERVAL_CEILING, 30000).toInt
      new ExponentialBackoffRetry(baseSleepTimeMs, maxRetries, maxSleepMs)
    }
    CuratorFrameworkFactory.newClient(connectString, sessionTimeoutMs, connectionTimeoutMs, retryPolicy)
  }

  private def createNodeCache(curator: CuratorFramework, path: String): Try[NodeCache] =
    Try { new NodeCache(curator, path) }

  private def getConfig[T](stormConf: util.Map[_, _], key: String, default: T): String =
    Option(stormConf.get(key)).getOrElse(default).toString

  override def execute(tuple: Tuple, collector: BasicOutputCollector): Unit = {
    if (killSwitchEnabled()) {
      val errorMsg = "Intentionally throwing this exception to trigger bolt failures"
      logger.error(errorMsg)
      throw new RuntimeException(errorMsg)
    }
    else {
      logger.info("Acking tuple {}", tuple.toString)
    }
  }

  override def declareOutputFields(declarer: OutputFieldsDeclarer) {
    declarer.declare(new Fields())
  }

  private def killSwitchEnabled(): Boolean = {
    val pathExists = for {
      c <- curator
      n <- nodeCache
    } yield n.getCurrentData != null
    pathExists match {
      // If the path exists then the kill switch is disabled.
      case Some(exists) => !exists
      case _ => KillSwitchEnabledDefault
    }
  }

}