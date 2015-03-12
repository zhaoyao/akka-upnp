package akkaupnp

import java.net.{NetworkInterface, InetAddress, InetSocketAddress}
import java.util.Collections

import akka.actor.{Props, ActorSystem}
import akka.util.Timeout
import akkaupnp.Upnp.{AddPortMapping, DelPortMapping, GetExternalIPAddress}

import scala.collection.JavaConverters._
import scala.concurrent.duration.DurationInt
import scala.xml.XML

import akka.pattern._

/**
 * User: zhaoyao
 * Date: 3/11/15
 * Time: 22:24
 */
object Main extends App {

  val system = ActorSystem()
  val upnp = system.actorOf(Props[Upnp])

  import system.dispatcher

  implicit val timeout = Timeout(1.minutes)

  (upnp ? AddPortMapping("TCP", 7751, 7750, "test", 36000)).onFailure {
    case e => e.printStackTrace()
  }
//  (upnp ? DelPortMapping("TCP", 7751)).onFailure {
//    case e => e.printStackTrace()
//  }

  system.awaitTermination()
  system.shutdown()


}
