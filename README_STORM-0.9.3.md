# What will happen when running the bolt-of-death topology in Storm 0.9.3

In short: This topology will trigger a cascading failure as described in
[STORM-329](https://issues.apache.org/jira/browse/STORM-320).


## Effect in a nutshell

A single "broken" bolt -- here: the bolt-of-death -- will behave like a contagious disease, which causes a snowball
effect.  First, it will spread its death to its direct upstream and downstream peers in the topology, then to the
"friends of friends" and so on.  Eventually it may bring down the whole topology, and in the worst case it will even
bring down other topologies in the cluster, too, to the point where the full Storm cluster may enter into an
unrecoverable zombie state where no processing will take place.

The probability of this cascading failure to happen seems to increase with the parallelism of a topology and/or with
the size of the Storm cluster.

There are six phases, and you may see some or all of them depending on the test conditions.

1. First the bolt-of-death will die (which is by design) as soon as it receives input tuples.  Storm will then, after
   a certain amount of time, restart the bolt's instances.
2. Then the bolt's direct upstream and downstream spouts/bolts, if any, will enter reconnection loops and may eventually
   die themselves.  Storm bolts are designed to try to reconnect to their peers for a certain amount of time (by default:
   5 mins for a total of 300 connection attempts) and, if reconnecting failed, to die themselves, at which point they
   will be restarted.  Reconnecting typically fails because the reconnection targets (here: the bolt-of-death instances)
   were restarted in different worker processes, i.e. different machine+port pairs.  These reconnect-until-death
   behavior may happen repeatedly, i.e. "loop".
3. Then other spouts/bolts -- "friends of friends" and so on -- may enter reconnect-die-restart loops.
4. Eventually the full topology may become disfunctional, a zombie: not dead but not alive either.
5. Other topologies may then become zombies, too.
6. The full Storm cluster may enter a zombie mode, which may be unrecoverable without a full cluster restart.

> Funny anecdote: Because of a race condition scenario you may even observe that Storm spouts/bolts will begin to talk
> to the wrong peers, e.g. `wordSpout` will talk directly to `bolt-of-death-A2`, even though this violates the wiring
> of our topology!  Read further details below.


## Effect in more detail

First, the bolt-of-death itself will (intentionally) die, and will keep dieing repeatedly as long as the topology runs.
In the Storm UI you will see that the bolt with ID `bolt-of-death-A2` will a) report runtime exceptions and b) the
uptime of the bolt will typically be reported as `<1 min` (because a runtime exception will kill a bolt instance).

Second, any direct upstream and/or downstream peers of the bolt-of-death instances will begin to die, too.  In this
example topology, we only have a single upstream bolt defined, which as the ID `forwarder-A1`.  Here, you will -- with
some delay -- observe that instances of this forwarder bolt will begin to die themselves as a result of
`bolt-of-death-A2` crashes.  In the Storm UI you will see that this bolt, too, will begin to report uptimes that will
typically be `<3mins` once the death spiral is in full swing.

Also, even though the `forwarder-A1` bolt will report (say) 2 minutes of uptime, it will not actually process data,
which you can confirm in the Storm UI (the bolt will not emit or transfer tuples).  The latter happens because the bolt
will be stuck (not necessarily always, but quite often; and the likelihood increases with the parallelism of the
topology) in a Netty reconnect loop, which is a _blocking call_.  For up to a default of 5 minutes (cf.
`storm.messaging.netty.max_retries` times `storm.messaging.netty.max_wait_ms`), any bolt in Storm will try to reconnect
(via Netty) to its peers in case of a connection loss.  During this reconnection phase the bolt will not perform any
processing.

Example log snippet:

```
2015-01-22 13:06:43 b.s.m.n.Client [INFO] Reconnect started for Netty-Client-supervisor2/10.0.0.102:6703... [0]
2015-01-22 13:06:43 b.s.m.n.Client [INFO] Reconnect started for Netty-Client-supervisor2/10.0.0.102:6703... [1]
2015-01-22 13:06:43 b.s.m.n.Client [INFO] Reconnect started for Netty-Client-supervisor2/10.0.0.102:6703... [2]
```


And if the connection cannot be reestablished again -- because e.g. the new "target" bolt instances that the bolt
wants to connect to are running elsewhere now -- then the bolt will die itself, oops!

```
2015-01-22 13:09:54 b.s.m.n.Client [INFO] Reconnect started for Netty-Client-supervisor2/10.0.0.102:6703... [300]
2015-01-22 13:09:55 b.s.m.n.Client [INFO] Closing Netty Client Netty-Client-supervisor2/10.0.0.102:6703
2015-01-22 13:09:55 b.s.m.n.Client [INFO] Waiting for pending batchs to be sent with Netty-Client-supervisor2/10.0.0.102:6703..., timeout: 600000ms, pendings: 0
2015-01-22 13:09:55 b.s.util [ERROR] Async loop died!
java.lang.RuntimeException: java.lang.RuntimeException: Remote address is not reachable. We will close this client Netty-Client-supervisor2/10.0.0.102:6703
```

> Depending on e.g. the parallelism of this topology, you will eventually also notice errors in the upstream spout as
> well as in the single bolt in the processing branch "B" of the topology.  We also observed that at some point the
> `wordSpout` instances would falsely try to (re-)connect to a worker process that is now being used to run a
> `bolt-of-death-A2` instance;  this is likely caused by the destination worker process switching task assignments
> (e.g. from running `forwarder-bolt-A1` to `bolt-of-death-A2`) while the spout instances are starting/entering their
> reconnection loop.  Note that this means we have suddenly Storm components violating our topology wiring because they
> are talking to the wrong peers!

Lastly, note how the "stuck" bolt instances will still report an increasing uptime, i.e. Storm will falsely believe
that the bolt instances are fine -- which will increase the probability that a bolt instances will be stuck for
the maximum duration of the reconnection attempt window (5 minutes, see above).  And while these instances are stuck in
a reconnection loop, a typical topology will become to experience back pressure as upstream tuples will begin queueing
up as the downstream processing has grinded to a halt;  at this point message timeouts etc. will happen on top of the
existing cascading failure.

> Also, if you run more than 1 executor and/or 1 task per worker process (= JVM) in your topology, then if one of the
> tasks/executors brings down the JVM it will also lead to the death of the remaining tasks/executors.

This reconnect-until-death problem is the main reason that will eventually cause a cascade of errors, because this
increases the probability that further bolts will run into the reconnect-until-death issue, and at some this snowball
effect will take down the whole topology.  We have even observed that a single "killer" topology will begin to effect
other topologies to the point where the full Storm cluster enters an unrecoverable zombie state where no processing is
taking place.  We are not certain yet when/what leads to this worst case, but it might have to do with other topologies
being sucked into the death spiral of the "killer" topology when their own spout/bolt instances need to be restarted,
and they end up talking to the same machines and/or machine+port combinations that are already affected.


## Excerpt of a worker log

The log snippet below shows the Netty reconnection loop of a worker process.

By [default](https://github.com/apache/incubator-storm/blob/master/conf/defaults.yaml) Storm uses Netty as its
transport backend.  Here, the settings:

* `storm.messaging.netty.max_retries` (default: `300`)
* `storm.messaging.netty.max_wait_ms` (default: `1000`)
* `storm.messaging.netty.min_wait_ms` (default: `100`)

determine how often and in which intervals a Storm bolt instance will try to connect to any of its peers for data
communication.

That why you will soon see messages such as the following.  Here, the worker process `supervisor3:6701` is trying in
vain to connect via Netty to the worker process running on `supervisor2:6703`:

```
2015-01-22 13:06:43 b.s.m.n.Client [INFO] Reconnect started for Netty-Client-supervisor2/10.0.0.102:6703... [0]
2015-01-22 13:06:43 b.s.m.n.Client [INFO] Reconnect started for Netty-Client-supervisor2/10.0.0.102:6703... [1]
2015-01-22 13:06:43 b.s.m.n.Client [INFO] Reconnect started for Netty-Client-supervisor2/10.0.0.102:6703... [2]
<snip>
2015-01-22 13:09:53 b.s.m.n.Client [INFO] Reconnect started for Netty-Client-supervisor2/10.0.0.102:6703... [299]
2015-01-22 13:09:54 b.s.m.n.Client [INFO] Reconnect started for Netty-Client-supervisor2/10.0.0.102:6703... [300]
2015-01-22 13:09:55 b.s.m.n.Client [INFO] Closing Netty Client Netty-Client-supervisor2/10.0.0.102:6703
2015-01-22 13:09:55 b.s.m.n.Client [INFO] Waiting for pending batchs to be sent with Netty-Client-supervisor2/10.0.0.102:6703..., timeout: 600000ms, pendings: 0
2015-01-22 13:09:55 b.s.util [ERROR] Async loop died!
java.lang.RuntimeException: java.lang.RuntimeException: Remote address is not reachable. We will close this client Netty-Client-supervisor2/10.0.0.102:6703
  at backtype.storm.utils.DisruptorQueue.consumeBatchToCursor(DisruptorQueue.java:128) ~[storm-core-0.9.3.jar:0.9.3]
  at backtype.storm.utils.DisruptorQueue.consumeBatchWhenAvailable(DisruptorQueue.java:99) ~[storm-core-0.9.3.jar:0.9.3]
  at backtype.storm.disruptor$consume_batch_when_available.invoke(disruptor.clj:80) ~[storm-core-0.9.3.jar:0.9.3]
  at backtype.storm.disruptor$consume_loop_STAR_$fn__1460.invoke(disruptor.clj:94) ~[storm-core-0.9.3.jar:0.9.3]
  at backtype.storm.util$async_loop$fn__464.invoke(util.clj:463) ~[storm-core-0.9.3.jar:0.9.3]
  at clojure.lang.AFn.run(AFn.java:24) [clojure-1.5.1.jar:na]
  at java.lang.Thread.run(Thread.java:745) [na:1.7.0_75]
Caused by: java.lang.RuntimeException: Remote address is not reachable. We will close this client Netty-Client-supervisor2/10.0.0.102:6703
  at backtype.storm.messaging.netty.Client.connect(Client.java:171) ~[storm-core-0.9.3.jar:0.9.3]
  at backtype.storm.messaging.netty.Client.send(Client.java:200) ~[storm-core-0.9.3.jar:0.9.3]
  at backtype.storm.utils.TransferDrainer.send(TransferDrainer.java:54) ~[storm-core-0.9.3.jar:0.9.3]
  at backtype.storm.daemon.worker$mk_transfer_tuples_handler$fn__3730$fn__3731.invoke(worker.clj:330) ~[storm-core-0.9.3.jar:0.9.3]
  at backtype.storm.daemon.worker$mk_transfer_tuples_handler$fn__3730.invoke(worker.clj:328) ~[storm-core-0.9.3.jar:0.9.3]
  at backtype.storm.disruptor$clojure_handler$reify__1447.onEvent(disruptor.clj:58) ~[storm-core-0.9.3.jar:0.9.3]
  at backtype.storm.utils.DisruptorQueue.consumeBatchToCursor(DisruptorQueue.java:125) ~[storm-core-0.9.3.jar:0.9.3]
  ... 6 common frames omitted
2015-01-22 13:09:55 b.s.util [ERROR] Halting process: ("Async loop died!")
java.lang.RuntimeException: ("Async loop died!")
  at backtype.storm.util$exit_process_BANG_.doInvoke(util.clj:325) [storm-core-0.9.3.jar:0.9.3]
  at clojure.lang.RestFn.invoke(RestFn.java:423) [clojure-1.5.1.jar:na]
  at backtype.storm.disruptor$consume_loop_STAR_$fn__1458.invoke(disruptor.clj:92) [storm-core-0.9.3.jar:0.9.3]
  at backtype.storm.util$async_loop$fn__464.invoke(util.clj:473) [storm-core-0.9.3.jar:0.9.3]
  at clojure.lang.AFn.run(AFn.java:24) [clojure-1.5.1.jar:na]
  at java.lang.Thread.run(Thread.java:745) [na:1.7.0_75]
```

At this point the bolt instance (that is trying to connect to a dead peer) will die itself:

```
2015-01-22 13:09:55 b.s.d.worker [INFO] Shutting down worker bolt-of-death-topology-3-1421931903 1b8a5c9e-fab7-4cda-b5f8-849c7e647a88 6701
2015-01-22 13:09:55 b.s.m.n.Client [INFO] Closing Netty Client Netty-Client-supervisor4/10.0.0.104:6703
2015-01-22 13:09:55 b.s.m.n.Client [INFO] Waiting for pending batchs to be sent with Netty-Client-supervisor4/10.0.0.104:6703..., timeout: 600000ms, pendings: 0
2015-01-22 13:09:55 b.s.m.n.Client [INFO] Closing Netty Client Netty-Client-supervisor3/127.0.1.1:6703
2015-01-22 13:09:55 b.s.m.n.Client [INFO] Waiting for pending batchs to be sent with Netty-Client-supervisor3/127.0.1.1:6703..., timeout: 600000ms, pendings: 0
2015-01-22 13:09:55 b.s.m.n.Client [INFO] Closing Netty Client Netty-Client-supervisor1/10.0.0.101:6703
2015-01-22 13:09:55 b.s.m.n.Client [INFO] Waiting for pending batchs to be sent with Netty-Client-supervisor1/10.0.0.101:6703..., timeout: 600000ms, pendings: 0
2015-01-22 13:09:55 b.s.d.worker [INFO] Shutting down receive thread
2015-01-22 13:09:55 o.a.s.c.r.ExponentialBackoffRetry [WARN] maxRetries too large (300). Pinning to 29
2015-01-22 13:09:55 b.s.u.StormBoundedExponentialBackoffRetry [INFO] The baseSleepTimeMs [100] the maxSleepTimeMs [1000] the maxRetries [300]
2015-01-22 13:09:55 b.s.m.n.Client [INFO] New Netty Client, connect to localhost, 6701, config: , buffer_size: 5242880
2015-01-22 13:09:55 b.s.m.n.Client [INFO] Reconnect started for Netty-Client-localhost/127.0.0.1:6701... [0]
2015-01-22 13:09:55 b.s.m.n.Client [INFO] connection established to a remote host Netty-Client-localhost/127.0.0.1:6701, [id: 0x821f475d, /127.0.0.1:57753 => localhost/127.0.0.1:6701]
2015-01-22 13:09:55 b.s.m.loader [INFO] Shutting down receiving-thread: [bolt-of-death-topology-3-1421931903, 6701]
2015-01-22 13:09:55 b.s.m.n.Client [INFO] Closing Netty Client Netty-Client-localhost/127.0.0.1:6701
2015-01-22 13:09:55 b.s.m.n.Client [INFO] Waiting for pending batchs to be sent with Netty-Client-localhost/127.0.0.1:6701..., timeout: 600000ms, pendings: 0
2015-01-22 13:09:55 b.s.m.loader [INFO] Waiting for receiving-thread:[bolt-of-death-topology-3-1421931903, 6701] to die
2015-01-22 13:09:55 b.s.m.loader [INFO] Shutdown receiving-thread: [bolt-of-death-topology-3-1421931903, 6701]
2015-01-22 13:09:55 b.s.d.worker [INFO] Shut down receive thread
2015-01-22 13:09:55 b.s.d.worker [INFO] Terminating messaging context
2015-01-22 13:09:55 b.s.d.worker [INFO] Shutting down executors
2015-01-22 13:09:55 b.s.d.executor [INFO] Shutting down executor forwarder-A1:[6 6]
```

Storm will then try to restart the bolt instance, which may or may not happen on the same machine/port (e.g.
`supervisor3:6701/tcp`) or even on the same machine (e.g. `supervisor3`).


