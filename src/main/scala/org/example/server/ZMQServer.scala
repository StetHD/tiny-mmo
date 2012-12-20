package org.example.server

import akka.actor._
import akka.zeromq._
import org.apache.thrift.transport.TIOStreamTransport
import org.apache.thrift.protocol.TBinaryProtocol
import com.typesafe.config.ConfigFactory
import org.example.models.{MongoBackedWorld, Id}
import org.example.protocol.Protocol
import akka.zeromq.Connect
import akka.zeromq.Listener
import akka.zeromq.Bind
import collection.generic.SeqFactory

// 'brew install zeromq'
object ZMQServer {

  val protocol = new Protocol {

    type Payload = Seq[Frame]

    val codec = new Codec[Payload] {
      /**
       * Decompose the TransportMessage and extracts its content
       * @param m the message decomposed
       * @return
       */
      def unapply(m: Payload) = {
        m match {
          case Seq(header, body@_*) =>
            val Seq(hint) = header.payload
            val bytes = body.map(_.payload.toArray).reduce(_++_)
            Some((hint, bytes))
          case unexpected =>
            throw new RuntimeException("Unexpected ZMQMessage format: " + unexpected)
        }
      }

      /**
       * Composes the message content into a TransportMessage
       * @param hint
       * @param bytes
       * @return
       */
      def apply(hint: Byte, bytes: Array[Byte]) =
        Seq(Frame(Seq(hint)), Frame(bytes.toSeq))
    }
  }

  val config = ConfigFactory.load()
  val system = ActorSystem.create("zmqsystem", config)
  // You must share this extension as 'context' to enable inproc:// transport
  // as inproc:// transports messages across threads sharing the same context.
  val extension = ZeroMQExtension(system)

  val authRouter = system.actorOf(Props[WorldRouter], name = "authRouter")
  val authDealer = system.actorOf(Props[WorldDealer], name = "authDealer")
  val authWorker = system.actorOf(Props[WorldWorker], name = "authWorker")
  val authReq = system.actorOf(Props[WorldReq], name = "authReq")

  val router = extension.newRouterSocket(Array(Bind("tcp://*:5560"), Listener(authRouter)))
  val dealer = extension.newDealerSocket(Array(Bind("inproc://workers"), Listener(authDealer)))
  val worker = extension.newDealerSocket(Array(Connect("inproc://workers"), Listener(authWorker)))
  val client = extension.newReqSocket(Array(Connect("tcp://127.0.0.1:5560"), Listener(authReq)))

  class WorldReq extends Actor with ActorLogging {
    override def preStart() {
      println("Req starting up on thread: " + Thread.currentThread().getName)
    }

    def receive: Receive = {
      case Connecting =>
        log.info("Connecting")
      case m: String =>
        log.info("message: " + m)
        client ! ZMQMessage(Seq(Frame(m)))
      case unexpected =>
        log.warning("Unexpected " + unexpected)
    }

    override def postStop() {
      println("postStop")
    }
  }

  /**
   * The router accepts client connections.
   */
  class WorldRouter extends Actor with ActorLogging {

    override def preStart() {
      println("Router starting up on thread: " + Thread.currentThread().getName)
    }

    def receive: Receive = {
      case m @ ZMQMessage(frames @ Seq(identity, _, body @_*)) =>
        log.info("message: " + m)
        dealer ! m
      case unexpected =>
        log.warning("Unexpected " + unexpected)
    }

    override def postStop() {
      println("postStop")
    }
  }

  /**
   * The dealer connects to router
   */
  class WorldDealer extends Actor with ActorLogging {

    override def preStart() {
      println("Dealer starting up on thread: " + Thread.currentThread().getName)
    }

    def receive: Receive = {
      case m @ ZMQMessage(frames @ Seq(identity, _, body @_*)) =>
        log.info("message: " + m)
        router ! m
      case unexpected =>
        log.warning("Unexpected " + unexpected)
    }

    override def postStop() {
      println("postStop")
    }
  }

  /**
   * The worker connects to the dealer
   */
  class WorldWorker extends Actor with ActorLogging {
    override def preStart() {
      println("Worker starting up on thread: " + Thread.currentThread().getName)
    }

    def receive: Receive = {
      case m @ ZMQMessage(Seq(identity, _, frames @_*)) =>
        import protocol._
        log.info("message: " + m)
        val idFrame = identity
        val emptyFrame = m.frames(1)
        val id = Id.fromByteArray(identity.payload.toArray)
        deserialize(frames) match {
          case m: serializers.thrift.Join =>
            val p = MongoBackedWorld.join(
              nickname = m.name,
              id = id.toString
            )
            // Notify the client that the client has successfully joined
            worker ! ZMQMessage(Seq(idFrame, emptyFrame, Frame("authenticated")))
            // Notify all currently connected clients that the new client has joined
            MongoBackedWorld.findExcept(id.toString).foreach { p =>
              val recipient = Frame(Id.fromString(p.id).toByteArray)
              val frames = serialize(new serializers.thrift.Joined(id.toString))
              val mm = ZMQMessage(Seq(recipient, emptyFrame) ++ frames)
              worker ! mm
            }
          case m: serializers.thrift.Move =>
            MongoBackedWorld.tryMove(id = id.toString, x = m.x)
            MongoBackedWorld.findExcept(id.toString).foreach { p =>
              val recipient = Frame(Id.fromString(p.id).toByteArray)
              val frames = serialize(new serializers.thrift.Moved(id.toString, m.x))
              val mm = ZMQMessage(Seq(recipient, emptyFrame) ++ frames)
              worker ! mm
            }
          case m: serializers.thrift.Leave =>
            MongoBackedWorld.leave(id = id.toString)
            MongoBackedWorld.findExcept(id.toString).foreach { p =>
              val recipient = Frame(Id.fromString(p.id).toByteArray)
              val frames = serialize(new serializers.thrift.Left(id.toString))
              val mm = ZMQMessage(Seq(recipient, emptyFrame) ++ frames)
              worker ! mm
            }
        }
      case unexpected =>
        log.warning("Unexpected " + unexpected)
    }

    override def postStop() {
      println("postStop")
    }
  }

}