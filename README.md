# storm-bolt-of-death [![Build Status](https://travis-ci.org/verisign/storm-bolt-of-death.svg?branch=master)](https://travis-ci.org/verisign/storm-bolt-of-death)

An [Apache Storm](http://storm.apache.org/) topology that will, by design, trigger failures at run-time.

The purpose of this bolt-of-death topology is to help testing Storm cluster stability.  It was originally created to
identify the issues surrounding the Storm defects described at
[STORM-329](https://issues.apache.org/jira/browse/STORM-320) and
[STORM-404](https://issues.apache.org/jira/browse/STORM-404).

---

Table of Contents

* <a href="#how-it-works">How this topology works</a>
* <a href="#usage">Usage</a>
    * <a href="#requirements">Requirements</a>
    * <a href="#packaging">Package the topology</a>
    * <a href="#submitting">Submit the topology to a Storm cluster</a>
    * <a href="#kill-switch">The Kill Switch</a>
    * <a href="#inspect">Inspect the topology and cluster health</a>
* <a href="#changelog">Change log</a>
* <a href="#contributing">Contributing</a>
* <a href="#license">License</a>
* <a href="#references">References</a>
    * <a href="#refs-related-storm-tickets">Related Storm tickets</a>
* <a href="#appendix">Appendix</a>
    * <a href="#appendix-storm-effects">Effects on certain Storm versions</a>

---


<a name="how-it-works"></a>

# How this topology works

The bolt-of-death topology is a simple "V" -shaped topology with two processing "branches" A and B:


```
                +-----> forwarder-A1 -----> bolt-of-death-A2    <<< branch A
                |
 wordSpout -----+
                |
                +-----> noop-B1                                 <<< branch B
```

If the topology's kill switch is enabled (which it is by default, see below for details), then the `execute()` method
of the [bolt-of-death](src/main/scala/com/verisign/storm/tools/sbod/bolts/BoltOfDeath.scala) will throw a
`RuntimeException` every time it receives an input tuple; i.e. all instances of this bolt will fail all the time.

This behavior simulates situations such as:

* Bolts that intentionally fail fast on certain error conditions
    * Example: "I cannot talk to the database, so I will die now!"
    * Note that Storm is touted as a fail-fast application itself (e.g. you can `kill -9` the Nimbus daemon), and it
      is a common pattern to write your bolts in the same way.
* An unexpected error occurs in a bolt
    * Example: a bolt running out of memory due to an implementation bug



<a name="usage"></a>

# Usage


<a name="requirements"></a>

## Requirements

* Java JDK 7 (Oracle JDK 7 preferred).
    * If you need a different Java version, then you must change the `javaVersion` variable in [build.sbt](build.sbt)
      and rebuild the topology with relevant Java JDK.
* Storm versions: This topology runs on Storm 0.9.3 and 0.10.0-SNAPSHOT.  Other versions should work, too.


<a name="packaging"></a>

## Package the topology

    $ ./sbt assembly

    >>> Creates target/scala-2.10/storm-bolt-of-death-assembly-0.1.0.jar


<a name="submitting"></a>

## Submit the topology to a Storm cluster

The following command submits the topology to a remote, "real" Storm cluster.  The topology's name will default to
"bolt-of-death-topology".

> Note: Depending on your testing approach you may want to disable the topology's kill switch before submitting
> the topology.  See the next section for details.

    $ storm jar storm-bolt-of-death-assembly-0.1.0.jar com.verisign.storm.tools.sbod.BoltOfDeathTopology

The default parallelism of this topology will consume 10 slots in your Storm cluster (2 executors for each spout/bolt
plus 2 acker threads).


<a name="kill-switch"></a>

## The Kill Switch

To provide you with more control over this topology we support a "kill switch" for the bolt-of-death:

* If the znode path `/bolt-of-death-stop` **exists** in the ZooKeeper ensemble used by Storm, then the kill switch is
  **disabled**.  In this case the bolt-of-death will stop throwing `RuntimeException` intentionally and simply ack
  every input tuple instead.
* If the znode path **does not exist**, then the kill switch is **enabled**.  In this case the bolt-of-death will
  throw a `RuntimeException` whenever it receives an input tuple.

> By default the znode `/bolt-of-death-stop` should not exist in your ZooKeeper data, hence the kill switch will be
> **enabled** when the topology is submitted.

Here's how you can create this znode in ZooKeeper using the ZooKeeper CLI at the example of a
[Wirbelsturm](https://github.com/miguno/wirbelsturm) deployment of Storm:

```
# First, log into one of your ZooKeeper servers.
$ vagrant ssh zookeeper1

# Launch the ZooKeeper CLI
$ /usr/lib/zookeeper/bin/zkCli.sh
[zk: localhost:2181(CONNECTED) 0]

# DISABLE the kill switch by creating the znode
[zk: localhost:2181(CONNECTED) 0] create /bolt-of-death-stop foo

# ENABLE the kill switch by deleting the znode
[zk: localhost:2181(CONNECTED) 1] delete /bolt-of-death-stop
```


<a name="inspect"></a>

## Inspect the topology and cluster health

Use standard tools such as the Storm UI, the
[Storm REST API](https://github.com/apache/storm/blob/master/STORM-UI-REST-API.md),
and the Storm log files to understand what is happening.

We also include the [bod-status.sh](sh/bod-status.sh) script that prints key metrics of the bolt-of-death topology
to console.  This script requires `curl`, [jq](http://stedolan.github.io/jq/) 1.4+, and access to the Storm REST API
endpoint of your Storm cluster.  One way to run this script is to upload it to one of the Storm nodes and run it from
there.

> Note: You must configure the Storm REST API endpoint of your Storm cluster in the top section of `bod-status.sh`.

Example output of `bod-status.sh`:

```
$ ./bod-status.sh
================================================================================
bod-status.sh -- displays key metrics of bolt-of-death-topology
================================================================================
Topology: bolt-of-death-topology-3-1421931903
--------------------------------------------------------------------------------
{
  "executorsTotal": 10,
  "workersTotal": 10,
  "tasksTotal": 10,
  "uptime": "1d 0h 0m 54s",
  "status": "ACTIVE",
  "name": "bolt-of-death-topology",
  "encodedId": "bolt-of-death-topology-3-1421931903",
  "id": "bolt-of-death-topology-3-1421931903"
}
================================================================================
                +-----> forwarder-A1 -----> bolt-of-death-A2
                |
 wordSpout -----+
                |
                +-----> noop-B1
================================================================================
Metrics of wordSpout
--------------------------------------------------------------------------------
{"uptime":"3m 25s","worker":"supervisor4:6700","emitted":2040,"transferred":4080,"acked":0}
{"uptime":"3m 40s","worker":"supervisor1:6700","emitted":2180,"transferred":4360,"acked":0}
================================================================================
Metrics of forwarder-A1
--------------------------------------------------------------------------------
{"uptime":"6m 17s","worker":"supervisor1:6701","emitted":1860,"transferred":1860,"acked":1860}
{"uptime":"6m 14s","worker":"supervisor3:6701","emitted":1860,"transferred":1860,"acked":1840}
================================================================================
Metrics of bolt-of-death-A2
--------------------------------------------------------------------------------
{"uptime":"2m 56s","worker":"supervisor4:6703","emitted":0,"transferred":0,"acked":0}
{"uptime":"2m 56s","worker":"supervisor2:6700","emitted":0,"transferred":0,"acked":0}
================================================================================
Metrics of noop-B1
--------------------------------------------------------------------------------
{"uptime":"1d 0h 0m 40s","worker":"supervisor4:6701","emitted":0,"transferred":0,"acked":182740}
{"uptime":"18h 40m 27s","worker":"supervisor2:6701","emitted":0,"transferred":0,"acked":129440}
================================================================================
Metrics of __acker
--------------------------------------------------------------------------------
{"uptime":"18h 40m 20s","worker":"supervisor1:6703","emitted":0,"transferred":0,"acked":0}
{"uptime":"1d 0h 0m 38s","worker":"supervisor3:6703","emitted":0,"transferred":0,"acked":0}
================================================================================

TIP: You should also inspect the associated worker logs.
```

The script will also include information about the latest errors, if any, for each of the Storm components.
The following example output snippet shows the situation where the `bolt-of-death` instances run into errors.

```
================================================================================
Component: bolt-of-death-A2
================================================================================
Metrics of bolt-of-death-A2
--------------------------------------------------------------------------------
{"uptime":"1s","worker":"supervisor1:6702","emitted":0,"transferred":0,"acked":0,"failed":0}
{"uptime":"1s","worker":"supervisor3:6702","emitted":0,"transferred":0,"acked":0,"failed":0}
--------------------------------------------------------------------------------
Latest errors of bolt-of-death-A2
--------------------------------------------------------------------------------
{"errorLapsedSecs":3,"worker":"supervisor1:6702","error":"java.lang.RuntimeException: java.lang.RuntimeException: Intentionally throwing this exception to trigger bolt failures\n\tat backtype.storm.utils.Disrup"}
{"errorLapsedSecs":6,"worker":"supervisor3:6702","error":"java.lang.RuntimeException: java.lang.RuntimeException: Intentionally throwing this exception to trigger bolt failures\n\tat backtype.storm.utils.Disrup"}
{"errorLapsedSecs":15,"worker":"supervisor1:6702","error":"java.lang.RuntimeException: java.lang.RuntimeException: Intentionally throwing this exception to trigger bolt failures\n\tat backtype.storm.utils.Disrup"}
{"errorLapsedSecs":17,"worker":"supervisor3:6702","error":"java.lang.RuntimeException: java.lang.RuntimeException: Intentionally throwing this exception to trigger bolt failures\n\tat backtype.storm.utils.Disrup"}
================================================================================
```


<a name="changelog"></a>

# Change log

See [CHANGELOG](CHANGELOG.md).


<a name="contributing"></a>

# Contributing to storm-bolt-of-death

All contributions are welcome: ideas, documentation, code, patches, bug reports, feature requests etc.  And you don't
need to be a programmer to speak up!

If you are new to GitHub please read [Contributing to a project](https://help.github.com/articles/fork-a-repo) for how
to send patches and pull requests to storm-bolt-of-death.


<a name="license"></a>

# License

Copyright © 2015 [VeriSign, Inc.](http://www.verisigninc.com/).

See [LICENSE](LICENSE) for licensing information.


<a name="references"></a>

# References


<a name="refs-related-storm-tickets"></a>

## Related Storm tickets

The bolt-of-death topology is able to trigger the stability problems described in e.g.:

* [STORM-329](https://issues.apache.org/jira/browse/STORM-320):
  Add Option to Config Message handling strategy when connection timeout
* [STORM-404](https://issues.apache.org/jira/browse/STORM-404):
  Worker on one machine crashes due to a failure of another worker on another machine
* [STORM-510](https://issues.apache.org/jira/browse/STORM-510):
  Netty messaging client blocks transfer thread on reconnect


<a name="appendix"></a>

# Appendix


<a name="appendix-storm-effects"></a>

## Effects on certain Storm versions

* [bolt-of-death topology and Storm 0.9.3](README_STORM-0.9.3.md)
