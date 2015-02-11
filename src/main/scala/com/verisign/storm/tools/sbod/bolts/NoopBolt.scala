package com.verisign.storm.tools.sbod.bolts

import backtype.storm.topology.base.BaseBasicBolt
import backtype.storm.topology.{BasicOutputCollector, OutputFieldsDeclarer}
import backtype.storm.tuple.{Fields, Tuple}
import com.verisign.storm.tools.sbod.logging.TransientLazyLogging

/**
 * No-operation bolt: apart from acking any received input tuples this bolt does nothing.
 */
class NoopBolt extends BaseBasicBolt with TransientLazyLogging {

  override def execute(tuple: Tuple, collector: BasicOutputCollector): Unit = {
    // Don't do anything but implicitly acking the received input tuple.
    logger.info("Acking tuple {}", tuple.toString)
  }

  override def declareOutputFields(declarer: OutputFieldsDeclarer) {
    declarer.declare(new Fields())
  }

}