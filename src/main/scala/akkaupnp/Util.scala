package akkaupnp

import java.net.{Inet4Address, InetAddress, NetworkInterface}
import java.util.Collections

import scala.collection.JavaConverters._

/**
 * User: zhaoyao
 * Date: 3/12/15
 * Time: 22:14
 */
object Util {

  def getLocalIPv4Addr(): Option[InetAddress] = {
    val ifs = NetworkInterface.getNetworkInterfaces
    for (ifd <- Collections.list(ifs).asScala) {
      if (!ifd.isLoopback && !ifd.isVirtual && !ifd.isPointToPoint && ifd.isUp
            && ifd.getName.startsWith("en")) { // TODO 网络设备的命名原则是什么?
        val addr = Collections.list(ifd.getInetAddresses).asScala
          .filter(_.isInstanceOf[Inet4Address]).headOption

        if (addr.isDefined) return addr
      }
    }

    None
  }
}
