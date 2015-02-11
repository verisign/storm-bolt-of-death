package com.verisign.storm.tools.sbod

import backtype.storm.Config
import backtype.storm.testing.TestWordSpout
import backtype.storm.topology.TopologyBuilder
import com.verisign.storm.tools.sbod.bolts.{BoltOfDeath, ForwarderBolt, NoopBolt}
import com.verisign.storm.tools.sbod.logging.LazyLogging
import com.verisign.storm.tools.sbod.utils.StormRunner

import scala.concurrent.duration._

/**
 * The purpose of this topology is to fail by design in order to help with testing and troubleshooting Storm stability
 * issues.
 */
class BoltOfDeathTopology(topologyName: String = "bolt-of-death-topology",
                          numWorkers: Int = 10,
                          numAckers: Int = 2,
                          spoutParallelism: Int = 2,
                          boltA1Parallelism: Int = 2,
                          boltA2Parallelism: Int = 2,
                          boltB1Parallelism: Int = 2,
                          killSwitchZnode: String = "/bolt-of-death-stop",
                          localRuntimeInSeconds: Duration = 30.minutes) {

  // We create a simple "V" -shaped topology with two processing "branches" A and B:
  //
  //                  +-----> forwarder-A1 ----> bolt-of-death-A2    <<< branch A
  //                  |
  //   wordSpout -----+
  //                  |
  //                  +-----> noop-B1                                <<< branch B
  //
  //
  private def buildTopology() = {
    val builder = new TopologyBuilder()

    val spoutId = "wordSpout"
    builder.setSpout(spoutId, new TestWordSpout, spoutParallelism)
    builder.createTopology()

    // Wire the first processing branch, "A"
    val boltA1Id = "forwarder-A1"
    builder.setBolt(boltA1Id, new ForwarderBolt, boltA1Parallelism).shuffleGrouping(spoutId)

    val boltA2Id = "bolt-of-death-A2"
    builder.setBolt(boltA2Id, new BoltOfDeath(killSwitchZnode), boltA2Parallelism).shuffleGrouping(boltA1Id)

    // Wire the second processing branch, "B"
    val boltB1Id = "noop-B1"
    builder.setBolt(boltB1Id, new NoopBolt, boltB1Parallelism).shuffleGrouping(spoutId)

    builder.createTopology()
  }

  private def topologyConfig: Config = {
    val c = new Config
    c.setNumWorkers(numWorkers)
    c.setNumAckers(numAckers)
    c
  }

  def runLocally() {
    StormRunner.runTopologyLocally(buildTopology(), topologyName, topologyConfig, localRuntimeInSeconds)
  }

  def runRemotely() {
    StormRunner.runTopologyRemotely(buildTopology(), topologyName, topologyConfig)
  }

}

object BoltOfDeathTopology extends LazyLogging {

  /**
   * Submits (runs) the topology.
   *
   * Usage: "BoltOfDeathTopology [topology-name] [local|remote]"
   *
   * By default, the topology is run remotely under the name "bolt-of-death-topology".
   *
   * Examples:
   *
   * {{{
   * # Runs in remote/cluster mode, with topology name "bolt-of-death-topology"
   * $ storm jar storm-bolt-of-death-assembly.jar com.verisign.storm.tools.sbod.BoltOfDeathTopology
   *
   * # Runs in remote/cluster mode, with topology name "foobar"
   * $ storm jar storm-bolt-of-death-assembly.jar com.verisign.storm.tools.sbod.BoltOfDeathTopology foobar
   *
   * # Runs in remote/cluster mode, with topology name "foobar"
   * $ storm jar storm-bolt-of-death-assembly.jar com.verisign.storm.tools.sbod.BoltOfDeathTopology foobar remote
   *
   * # Runs in local mode (LocalCluster), with topology name "fail-topology"
   * $ storm jar storm-bolt-of-death-assembly.jar com.verisign.storm.tools.sbod.BoltOfDeathTopology fail-topology local
   * }}}
   *
   * @param args First positional argument (optional) is topology name, second positional argument (optional) defines
   *             whether to run the topology locally ("local") or remotely, i.e. on a real cluster ("remote").
   */
  def main(args: Array[String]) {
    var topologyName: String = "bolt-of-death-topology"
    if (args.length >= 1) {
      topologyName = args(0)
    }
    var runRemotely: Boolean = true
    if (args.length >= 2 && args(1).equalsIgnoreCase("local")) {
      runRemotely = false
    }
    logger.info("Topology name: " + topologyName)
    val topology: BoltOfDeathTopology = new BoltOfDeathTopology(topologyName)
    if (runRemotely) {
      logger.info("Running in remote (cluster) mode")
      topology.runRemotely()
    }
    else {
      logger.info("Running in local mode")
      topology.runLocally()
    }
  }

}