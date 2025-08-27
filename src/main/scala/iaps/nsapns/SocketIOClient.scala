package iaps.nsapns

import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter

import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

import java.time.Instant
import scala.annotation.unused
import scala.collection.mutable.ListBuffer
import scala.util.Try

trait SocketIOClient {

  def connect(): Unit
  def disconnect(): Unit

}

object SocketIOClient {

  def create(
    ns: NightscoutConnection,
    onConnecting: () => Unit,
    onConnected: () => Unit,
    onAuthFailed: (String) => Unit,
    @unused onDisconnected: () => Unit,
    onError: () => Unit,
    onUpdate: ((Instant, Int)) => Unit,
  ): SocketIOClient = new SocketIOClient {
//    import java.util.logging.Level
//    import java.util.logging.Logger
//
//    Logger.getLogger("io.socket").setLevel(Level.FINEST)
//    Logger.getLogger("").getHandlers.foreach(_.setLevel(Level.FINEST))

    val socketRef = new AtomicReference[Socket]()

    def connect(): Unit = {

      if socketRef.get() != null then {
        return
      }

      val nsBase = ns.nsBase

      val uri = new URI(nsBase.renderString)

      val opts = IO.Options
        .builder()
        .setTransports(Array("websocket", "polling")) // prefer WS, fall back to XHR
        .setReconnection(true)
        .setReconnectionAttempts(Integer.MAX_VALUE)
        .setReconnectionDelay(10000) // 1s -> 10s backoff
        .setReconnectionDelayMax(30000)
        .setForceNew(false)
        .build()

      val socket: Socket = IO.socket(uri, opts)
      if !socketRef.compareAndSet(null, socket) then {
        return
      }

      socket.on(
        Socket.EVENT_CONNECT,
        new Emitter.Listener {
          def call(args: Object*): Unit = {
            onConnecting()
            val authorization       = new JSONObject()
              .put("client", "web")
              .put("secret", ns.apiSecretSha1)
//                .put("token", ns.apiToken.getOrElse("no token"))
              .put("history", 0)
            val args: Array[Object] = Array(authorization)

            val _ = socket.emit(
              "authorize",
              args,
              new Ack {
                def call(payload: Object*): Unit = {
//                  println(s"NS authorize ack: ${payload.take(200)}…")
                  payload.headOption match {
                    case Some(o: JSONObject) =>
                      Try(o.getBoolean("read")).toOption match {
                        case Some(true)  =>
//                          println(s"NS authorized...")
                          onConnected()
                        case Some(false) =>
                          onAuthFailed("no read permission")
                          disconnect()
                        case None        =>
                          println(s"unexpected authorization ack: ${o}")
                          onAuthFailed("authorization failed")
                          disconnect()
                      }
                    case _                   =>
                      onAuthFailed("authorization failed")
                      disconnect()
                  }
                }
              }
            )

          }
        }
      )

//        socket.onAnyIncoming(
//          new Emitter.Listener {
//            override def call(payload: Object*): Unit =
//              println(s"NS socket any incoming: ${String.valueOf(payload).take(1000)}…")
//          }
//        )

      socket.on(
        Socket.EVENT_DISCONNECT,
        new Emitter.Listener {
          override def call(args: Object*): Unit = {
            println(s"NS socket disconnected: ${String.valueOf(args)}")
          }
        }
      )

      socket.on(
        Socket.EVENT_CONNECT_ERROR,
        new Emitter.Listener {
          override def call(args: Object*): Unit = {
            println("NS socket connect error")
            onError()
          }
        }
      )

      socket.on(
        "dataUpdate",
        new Emitter.Listener {
          override def call(args: Object*): Unit = {
            args.headOption.foreach {
              case payload: JSONObject =>
                Try(payload.getJSONArray("sgvs")).toOption.foreach { sgvs =>
                  val list = ListBuffer[(Long, Int)]()
                  var i    = 0
                  while i < sgvs.length do {
                    Try(sgvs.getJSONObject(i)).toOption.foreach { sgv =>
                      Try(sgv.getLong("mills")).toOption.foreach { millis =>
                        Try(sgv.getInt("mgdl")).toOption.foreach { mgdl =>
                          list.addOne((millis, mgdl))
                        }
                      }
                      i += 1
                    }
                  }
                  list.toList.maxByOption(_._1).foreach { (millis, mgdl) =>
                    val timestamp = Instant.ofEpochMilli(millis)
                    onUpdate((timestamp, mgdl))
                  }
                }
              case _                   => // noop
            }
          }
        }
      )

      val _ = socket.connect()
    }

    def disconnect(): Unit = {
      val socket = socketRef.getAndSet(null)
      if socket == null then {
        return
      }
      val _      = socket.disconnect()
    }

  }

}
