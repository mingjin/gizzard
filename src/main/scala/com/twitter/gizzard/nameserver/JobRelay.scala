package com.twitter.gizzard
package nameserver

import java.util.{LinkedList => JLinkedList}
import java.nio.ByteBuffer
import com.twitter.rpcclient.LoadBalancingChannel
import com.twitter.conversions.time._
import com.twitter.util.Duration

import scheduler.JsonJob
import thrift.{JobInjector, JobInjectorClient}

class ClusterBlockedException(cluster: String, cause: Throwable)
extends Exception("Job replication to cluster '" + cluster + "' is blocked.", cause) {
  def this(cluster: String) = this(cluster, null)
}

class JobRelayFactory(
  priority: Int,
  framed: Boolean,
  timeout: Duration,
  retries: Int)
extends (Map[String, Seq[Host]] => JobRelay) {

  def this(priority: Int, framed: Boolean, timeout: Duration) = this(priority, framed, timeout, 0)

  def apply(hostMap: Map[String, Seq[Host]]) =
    new JobRelay(hostMap, priority, framed, timeout, retries)
}

class JobRelay(
  hostMap: Map[String, Seq[Host]],
  priority: Int,
  framed: Boolean,
  timeout: Duration,
  retries: Int)
extends (String => JobRelayCluster) {

  private val clients = hostMap.flatMap { case (c, hs) =>
    var blocked = false
    val onlineHosts = hs.filter(_.status match {
      case HostStatus.Normal     => true
      case HostStatus.Blocked    => { blocked = true; false }
      case HostStatus.Blackholed => false
    })

    if (onlineHosts.isEmpty) {
      if (blocked) Map(c -> new BlockedJobRelayCluster(c)) else Map[String, JobRelayCluster]()
    } else {
      Map(c -> new JobRelayCluster(onlineHosts, priority, framed, timeout, retries))
    }
  }

  val clusters = clients.keySet

  def apply(cluster: String) = clients.getOrElse(cluster, NullJobRelayCluster)
}

class JobRelayCluster(
  hosts: Seq[Host],
  priority: Int,
  framed: Boolean,
  timeout: Duration,
  retries: Int)
extends (Iterable[Array[Byte]] => Unit) {
  val client = new LoadBalancingChannel(hosts.map(h => new JobInjectorClient(h.hostname, h.port, framed, timeout, retries)))

  def apply(jobs: Iterable[Array[Byte]]) {
    val jobList = new JLinkedList[thrift.Job]()

    jobs.foreach { j =>
      val tj = new thrift.Job(priority, ByteBuffer.wrap(j))
      tj.setIs_replicated(true)
      jobList.add(tj)
    }

    client.proxy.inject_jobs(jobList)
  }
}

object NullJobRelayFactory extends JobRelayFactory(0, false, 0.seconds) {
  override def apply(h: Map[String, Seq[Host]]) = NullJobRelay
}

object NullJobRelay extends JobRelay(Map(), 0, false, 0.seconds, 0)

object NullJobRelayCluster extends JobRelayCluster(Seq(), 0, false, 0.seconds, 0) {
  override val client = null
  override def apply(jobs: Iterable[Array[Byte]]) = ()
}

class BlockedJobRelayCluster(cluster: String) extends JobRelayCluster(Seq(), 0, false, 0.seconds, 0) {
  override val client = null
  override def apply(jobs: Iterable[Array[Byte]]) { throw new ClusterBlockedException(cluster) }
}
