package akkaupnp

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, Stash}
import akka.io.Udp._
import akka.io.{IO, Udp}
import akka.pattern._
import akka.util.ByteString
import spray.client.pipelining._
import spray.http.HttpHeaders.RawHeader
import spray.http._

import scala.concurrent.Future
import scala.xml.{Elem, Node, XML}

object Upnp {

  val DiscoverRequest =
    "M-SEARCH * HTTP/1.1\r\n" +
      "HOST: 239.255.255.250:1900\r\n" +
      "ST: ssdp:all\r\n" +
      "MAN: \"ssdp:discover\"\r\n" +
      "MX: 2\r\n\r\n"

  val targetST = "InternetGatewayDevice:1"

  case object Init

  case class UpnpDevice(serviceUri: Uri, urnDomain: String)

  case class AddPortMapping(protocol: String,
                            externalPort: Int, internalPort: Int,
                            description: String, duration: Long, ip: Option[String] = None)

  case class DelPortMapping(protocol: String, externalPort: Int)

  case object GetExternalIPAddress

  class SoupException(msg: String) extends RuntimeException(msg)


  def soupRequestBuilder(device: UpnpDevice, func: String, body: Elem) = {
    val requestBody =
      <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                  s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
        <s:Body>
          {body}
        </s:Body>
      </s:Envelope>

    Post(device.serviceUri, requestBody.toString())
      .withHeaders(
        HttpHeaders.`Content-Type`(ContentType(MediaTypes.`application/xml`)),
        RawHeader("User-Agent", "Darwin/10.0.0, UPnP/1.0, MiniUPnPc/1.3"),
        RawHeader("SOAPAction", s""""urn:${device.urnDomain}:service:WANIPConnection:1#$func"""")
      )
  }
}

class Upnp extends Actor with ActorLogging with Stash {

  import akkaupnp.Upnp._
  import context.dispatcher

  override def preStart(): Unit = {
    self ! Init
  }

  def init: Receive = {
    case Init =>
      implicit val system = context.system
      IO(Udp) ! Bind(self, new InetSocketAddress(0))
      context become discover
    case _ => stash()
  }

  def discover: Receive = {
    case CommandFailed(cmd) =>
      log.error("Command failed: {}", cmd)
      context stop self

    case Bound(_) =>
      log.info("Upnp discover upd bind successfully")
      sender ! Send(ByteString(DiscoverRequest), new InetSocketAddress("239.255.255.250", 1900))

    case Received(data, _) =>
      val s = new String(data.toArray)
      //      log.info("Received: {}", s)
      if (s.contains("InternetGatewayDevice:1")) {
        val i = s.toLowerCase().indexOf("\r\nlocation:")
        if (i > 0) {
          val endIdx = s.indexOf("\r\n", i + "\r\nlocation:".length)
          if (endIdx > 0) {
            val loc = s.substring(i + "\r\nlocation:".length, endIdx).trim
            log.info("Found loc: {}", loc)
            parseWanDeviceInfo(loc).onSuccess {
              case Some(wanDevice) =>
                //found
                log.info("Found wan device => {}", wanDevice)

                sender() ! Unbind
                context become ready(wanDevice)
                unstashAll()

              case None =>
                log.info("Ignore upnp device => {}", loc)
            }
          }
        }
      }

    case _ => stash()
  }

  def ready(wanDevice: UpnpDevice): Receive = {
    case r: AddPortMapping =>
      addPortMapping(wanDevice, r)

    case r: DelPortMapping =>
      deletePortMapping(wanDevice, r)

    case GetExternalIPAddress =>
      pipe(getExternalIPAddress(wanDevice)).pipeTo(sender())

  }

  def addPortMapping(upnp: UpnpDevice, r: AddPortMapping) = {
    val ip = r.ip
              .orElse(Util.getLocalIPv4Addr().map(_.getHostAddress))
              .orElse(throw new IllegalArgumentException("Unable to choose client ip address"))

    //TODO 如果把 uns 这段表达式直接写到 xmlns:u={} 里面，会导致 "urn:" + upnp.urnDomain 不出现在最终的xml里面，何故?
    val uns = "urn:" + upnp.urnDomain + ":service:WANIPConnection:1"
    val body =
      <u:AddPortMapping xmlns:u={uns}>
        <NewRemoteHost></NewRemoteHost>
        <NewExternalPort>{r.externalPort}</NewExternalPort>
        <NewProtocol>{r.protocol}</NewProtocol>
        <NewInternalPort>{r.internalPort}</NewInternalPort>
        <NewInternalClient>{ip.get}</NewInternalClient>
        <NewEnabled>1</NewEnabled>
        <NewPortMappingDescription> {r.description} </NewPortMappingDescription>
        <NewLeaseDuration>{r.duration}</NewLeaseDuration>
      </u:AddPortMapping>

    sendSoapRequest(upnp, "AddPortMapping", body).map(_ => true)
  }

  def deletePortMapping(upnp: UpnpDevice, r: DelPortMapping) = {
    val body =
      <u:DeletePortMapping xmlns:u={"urn:" + upnp.urnDomain + ":service:WANIPConnection:1"}>
        <NewRemoteHost></NewRemoteHost>
        <NewExternalPort> {r.externalPort} </NewExternalPort>
        <NewProtocol>
          {r.protocol}
        </NewProtocol>
      </u:DeletePortMapping>

    sendSoapRequest(upnp, "DeletePortMapping", body).map(_ => true)
  }

  def getExternalIPAddress(upnp: UpnpDevice): Future[String] = {
    val body =
      <u:GetExternalIPAddress xmlns:u={"urn:" + upnp.urnDomain + ":service:WANIPConnection:1"}>
      </u:GetExternalIPAddress>

    sendSoapRequest(upnp, "GetExternalIPAddress", body) map { envelope =>
      (envelope \ "Body" \ "GetExternalIPAddressResponse" \ "NewExternalIPAddress").text
    }
  }

  def sendSoapRequest(upnp: UpnpDevice, func: String, body: Elem): Future[Elem] = {
    val pipeline: HttpRequest => Future[HttpResponse] = sendReceive
    pipeline(soupRequestBuilder(upnp, func, body))
      .map(resp => {
      log.info("Soap response => ", resp.entity.asString)
      XML.loadString(resp.entity.asString)
    }).flatMap { result =>
      val fault = (result \ "Body" \ "Fault")
      if (fault.length > 0) {
        val faultCode = (fault \ "faultcode").text
        val faultString = (fault \ "faultstring").text
        val errorCode = (fault \ "detail" \ "UPnPError" \ "errorCode").text
        val errorDescription = (fault \ "detail" \ "UPnPError" \ "errorDescription").text
        Future.failed(new SoupException(s"Upnp $func error $faultCode[$faultString] $errorCode => $errorDescription"))
      } else {
        Future.successful(result)
      }
    }
  }

  def parseWanDeviceInfo(location: String): Future[Option[UpnpDevice]] = {
    val pipeline: HttpRequest => Future[HttpResponse] = sendReceive

    pipeline(Get(location)).map { resp =>
      import scala.xml._
      val xmlResp = XML.loadString(resp.entity.asString)
      childDevice((xmlResp \ "device").head, "WANDevice:1")
        .flatMap { wanDevice =>
        childDevice(wanDevice, "WANConnectionDevice:1").flatMap { wanConnectionDevice =>
          childService(wanConnectionDevice, "WANIPConnection:1").flatMap { wanIPConnection =>
            val urnDomain = (wanIPConnection \ "serviceType").text.split(":") match {
              case Array("urn", domain, _*) => Some(domain)
              case _ => None
            }

            urnDomain.flatMap { d =>
              val ctrlUrl = ((wanIPConnection) \ "controlURL").text
              Some(UpnpDevice(Uri(location).withPath(Uri.Path(ctrlUrl)), d))
            }
          }
        }
      }
    }
  }

  private def childDevice(n: Node, deviceType: String): Option[Node] = {
    n \ "deviceList" \ "device" find childTextMatch("deviceType", deviceType)
  }

  private def childService(n: Node, serviceType: String): Option[Node] = {
    n \ "serviceList" \ "service" find childTextMatch("serviceType", serviceType)
  }

  private def childTextMatch(name: String, value: String) = { n: Node =>
    (n \ name).text.contains(value)
  }

  override def receive: Receive = init
}
