package iaps.nsapns

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import org.http4s.client.*
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import fs2.io.file.Files
import okhttp3.OkHttpClient
import org.typelevel.log4cats.Logger

import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import scala.annotation.unused
import scala.concurrent.duration.*

trait NightscoutPoller[F[_]] {

  def restoreAll: F[Unit]

  def start(
    ns: NightscoutConnection
  ): F[Either[String, String]]

  def stop(
    ns: NightscoutConnection
  ): F[Unit]

  def stopAll: F[Unit]
}

object NightscoutPoller {

  private enum SocketIoEvent {

    case Authorizing
    case Connected
    case Update(timestamp: Instant, sgv: Int)
    case Error(err: String)
    case Disconnected
    case AuthFailed(err: String)

  }

  def apply[F[_]](
    serverConfig: ServerConfig,
    @unused client: Client[F],
    apns: ApnsManager[F]
  )(using F: Async[F], files: Files[F]): Resource[F, NightscoutPoller[F]] = {
    Resource.make(
      F.ref(Map.empty[String, (Fiber[F, Throwable, Unit], NightscoutConnection)]).flatMap { connections =>
        F.delay {

          val ok = new OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

          io.socket.client.IO.setDefaultOkHttpWebSocketFactory(ok)
          io.socket.client.IO.setDefaultOkHttpCallFactory(ok)

        } *>
          F.delay {

            new NightscoutPoller[F] {

              private val logger: Logger[F] = org.typelevel.log4cats.slf4j.Slf4jLogger.getLogger[F]

              private val storage = JsonStorage.create[F, NightscoutConnection](serverConfig.storage, "connections", _.deviceToken)

              def restoreAll: F[Unit] =
                storage.readAll.parEvalMapUnordered(10)(start).compile.drain

              def start(
                ns: NightscoutConnection
              ): F[Either[String, String]] = {
                apns.validateConnection(ns).flatMap {
                  case Left(error) => error.asLeft[String].pure[F]
                  case Right(_)    =>
                    connections.get
                      .flatMap { connections =>
                        F.delay {
                          connections.get(ns.deviceToken)
                        }
                      }
                      .flatMap {
                        case Some((_, running)) if running == ns => running.some.pure[F]
                        case Some((_, running))                  => stop(running).as(none)
                        case None                                => none.pure[F]
                      }
                      .flatMap {
                        case Some(_) => "already subscribed".asRight[String].pure[F]
                        case None    =>
//                        F.delay {
//                          NightscoutClient[F](client, ns)
//                        }.flatMap { nsClient =>
                          F.deferred[Either[String, String]].flatMap { result =>
                            F.ref(none[Instant]).flatMap { lastSeen =>

                              socketSubscribe(ns)
                                .evalTap {
                                  case SocketIoEvent.Authorizing           =>
                                    logger.info(s"${ns.deviceToken}: authorizing...")
                                  case SocketIoEvent.Connected             =>
                                    logger.info(s"${ns.deviceToken}: connection established") *>
                                      result.complete("connected".asRight[String]).void
                                  case SocketIoEvent.AuthFailed(err)       =>
                                    logger.info(s"${ns.deviceToken}: authorization failed: ${err}") *>
                                      result.complete(s"nightscout connection failed: ${err}".asLeft[String]).void
                                  case SocketIoEvent.Disconnected          =>
                                    logger.info(s"${ns.deviceToken}: diconnected") *>
                                      result.complete("nightscout disconnected".asLeft[String]).void
                                  case SocketIoEvent.Error(err)            =>
                                    logger.info(s"${ns.deviceToken}: connection error: ${err}") *>
                                      result.complete(s"nightscout connection error: ${err}".asLeft[String]).void
                                  case SocketIoEvent.Update(received, sgv) =>
                                    for {
                                      _      <- logger.info(s"${ns.deviceToken}: update received")
                                      latest <- lastSeen.get
                                      _      <- if latest.forall(_.isBefore(received)) then {
                                                  lastSeen.set(received.some) *>
                                                    apns.notify(
                                                      deviceToken = ns.deviceToken,
                                                      bundleId = ns.bundleId,
                                                      useSandbox = ns.useSandbox,
                                                      latestTimestamp = received,
                                                      sgv = sgv
                                                    )
                                                } else {
                                                  Async[F].unit
                                                }
                                    } yield ()
                                }
                                .onFinalize {
                                  logger.info(s"${ns.deviceToken}: connection ended") *>
                                    connections.update { connections =>
                                      connections.removed(ns.deviceToken)
                                    }
                                }
                                .compile.drain
                                .start
                                .flatMap { fiber =>
                                  connections.update(
                                    _ + (ns.deviceToken -> (fiber, ns))
                                  )
                                }
                                .flatMap { _ =>
                                  F.timeout(result.get, 10.seconds).recoverWith { case _: TimeoutException =>
                                    "nightscout server connection timeout".asLeft[String].pure[F]
                                  }
                                }
                                .flatTap {
                                  case Right(_) => storage.save(ns)
                                  case Left(_)  => stop(ns)
                                }
                              //                        .attemptTap { outcome =>
                              //                          logger.info(s"${ns.deviceToken}: connected: $outcome")
                              //                        }

                              //                  fs2.Stream
                              //                    .awakeEvery(15.seconds)
                              //                    .evalMap { _ =>
                              //                      nsClient.fetchEntriesSince(30.seconds, limit = 5)
                              //                    }
                              //                    .evalMap { result =>
                              //                      F.delay { result.sortBy(_.date).lastOption }.flatMap { latestEntry =>
                              //                        latestEntry.traverse_ { latestEntry =>
                              //                          latestEntry.sgv.traverse_ { sgv =>
                              //                            val received = Instant.ofEpochMilli(latestEntry.date)
                              //                            for {
                              //                              latest <- lastSeen.get
                              //                              _      <- if latest.forall(_.isBefore(received)) then {
                              //                                          lastSeen.set(received.some) *>
                              //                                            apns.notify(
                              //                                              deviceToken = ns.deviceToken,
                              //                                              teamId = ns.teamId,
                              //                                              bundleId = ns.bundleId,
                              //                                              useSandbox = ns.useSandbox,
                              //                                              latestTimestamp = received,
                              //                                              sgv = sgv
                              //                                            )
                              //                                        } else {
                              //                                          Async[F].unit
                              //                                        }
                              //                            } yield ()
                              //                          }
                              //                        }
                              //                      }
                              //                    }
                              //                    .compile
                              //                    .drain
                              //                    .flatMap { _ =>
                              //                      connections.update { connections =>
                              //                        connections.removed(ns)
                              //                      }
                              //                    }
                              //                    .start
                              //                    .flatMap { fiber =>
                              //                      connections.update(
                              //                        _ + (ns -> fiber)
                              //                      )
                              //                    }
                              //                    .void
                            }
                          }
//                          }
                      }
                }
              }

              def stop(
                ns: NightscoutConnection
              ): F[Unit] =
                connections
                  .modify { connections =>
                    val fiber = connections.get(ns.deviceToken)
                    (connections.removed(ns.deviceToken), fiber.map(_._1))
                  }
                  .flatMap { fiber =>
                    fiber.traverse_(_.cancel)
                  }
                  .flatMap { _ =>
                    storage.delete(ns)
                  }

              def stopAll: F[Unit] = connections
                .modify { connections =>
                  (Map.empty, connections)
                }
                .flatMap { connections =>
                  connections.values.toList.traverse_(_._1.cancel)
                }

              private def socketSubscribe(ns: NightscoutConnection): fs2.Stream[F, SocketIoEvent] = {
                fs2.Stream.resource(Dispatcher.sequential[F]).flatMap { dispatcher =>
                  fs2.Stream.eval(Queue.circularBuffer[F, Option[SocketIoEvent]](1)).flatMap { queue =>
                    fs2.Stream
                      .bracket(
                        F.async[SocketIOClient] { cb =>
                          F.delay {
                            val client = SocketIOClient.create(
                              ns,
                              onConnecting = () => {
                                dispatcher.unsafeRunSync(queue.offer(SocketIoEvent.Authorizing.some))
                              },
                              onConnected = () => {
                                dispatcher.unsafeRunSync(queue.offer(SocketIoEvent.Connected.some))
                                //                              cb(Right((client, stream)))
                              },
                              onAuthFailed = err => {
                                dispatcher.unsafeRunSync(
                                  queue.offer(SocketIoEvent.AuthFailed(err).some) *>
                                    queue.offer(none)
                                )
                              },
                              onDisconnected = () => {
                                //                    dispatcher.unsafeRunSync(
                                //                      queue.offer(SocketIoEvent.Disconnected.some) *>
                                //                        queue.offer(none)
                                //                    )
                              },
                              onError = () => {
                                dispatcher.unsafeRunSync(queue.offer(SocketIoEvent.Error("connection failed").some))
                              },
                              onUpdate = (timestamp, sgv) => {
                                dispatcher.unsafeRunSync(queue.offer(SocketIoEvent.Update(timestamp, sgv).some))
                              }
                            )
                            client.connect()

                            cb(Right(client))

                            Some(
                              F.delay {
                                client.disconnect()
                              }
                            )
                          }
                        }
                      )(client =>
                        logger.info("socket stream cancelled") *>
                          F.delay {
                            client.disconnect()
                          }
                      ).flatMap { _ =>
                        fs2.Stream.fromQueueNoneTerminated(queue)
                      }
                  }
                }
              }

            }
          }
      }
    )(_.stopAll)
  }

}
