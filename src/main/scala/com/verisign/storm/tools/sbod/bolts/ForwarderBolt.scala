package com.verisign.storm.tools.sbod.bolts

import backtype.storm.topology.base.BaseBasicBolt
import backtype.storm.topology.{BasicOutputCollector, OutputFieldsDeclarer}
import backtype.storm.tuple.{Fields, Tuple, Values}
import com.google.common.base.Throwables
import com.verisign.storm.tools.sbod.logging.TransientLazyLogging

import scala.util.{Failure, Success, Try}

/**
 * Reads a field from an input tuple and emits ("forwards") that field to downstream consumers.
 */
class ForwarderBolt(inputField: String = "word", outputField: String = "word")
    extends BaseBasicBolt with TransientLazyLogging {

  override def execute(tuple: Tuple, collector: BasicOutputCollector) {
    Try(tuple.getStringByField(inputField)) match {
      case Success(word) if word != null =>
        logger.info("Forwarding tuple {}", tuple.toString)
        collector.emit(new Values(word))
        () // to fix scalac warning about discarded non-Unit value
      case Success(_) => logger.error("Reading from input tuple returned null")
      case Failure(e) => logger.error("Could not read from input tuple: " + Throwables.getStackTraceAsString(e))
    }
  }

  override def declareOutputFields(declarer: OutputFieldsDeclarer) {
    declarer.declare(new Fields(inputField))
  }

}