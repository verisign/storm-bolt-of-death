package com.verisign.storm.tools.sbod.logging

import org.slf4j.{Logger, LoggerFactory}

trait TransientLazyLogging {

  @transient protected lazy val logger: Logger = LoggerFactory.getLogger(getClass.getName)

}

trait LazyLogging {

  protected lazy val logger: Logger = LoggerFactory.getLogger(getClass.getName)

}

trait StrictLogging {

  protected val logger: Logger = LoggerFactory.getLogger(getClass.getName)

}