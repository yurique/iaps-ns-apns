package iaps.nsapns

import cats.syntax.all.*
import cats.effect.*
import io.circe.Json

import java.time.Instant
import fs2.io.file.Files
import org.typelevel.log4cats.Logger

trait ApnsManager[F[_]] {

  def restoreRegistrations: F[Unit]
  def register(config: ApnsConfig): F[Either[String, Unit]]
  def unregister(config: ApnsConfig): F[Either[String, Unit]]
  def validateConnection(ns: NightscoutConnection): F[Either[String, Unit]]
  def notify(
    deviceToken: String,
    bundleId: String,
    useSandbox: Boolean,
    latestTimestamp: Instant,
    sgv: Int
  ): F[Unit]

}

object ApnsManager {

  def apply[F[_]](
    serverConfig: ServerConfig,
    client: ApnsClient[F]
  )(using F: Async[F], files: Files[F]): F[ApnsManager[F]] = {

    F.ref(
      Map.empty[String, ApnsConfig]
    ).flatMap { registry =>
        F.delay {

          new ApnsManager[F] {

            private val storage = JsonStorage.create[F, ApnsConfig](serverConfig.storage, "configs", _.bundleId)

            private val logger: Logger[F] = org.typelevel.log4cats.slf4j.Slf4jLogger.getLogger[F]

            private val BlankDeviceToken = "0000000000000000000000000000000000000000000000000000000000000000"

            private def validate(config: ApnsConfig): F[Option[String]] =
              client
                .sendBackgroundPush(config.bundleId, BlankDeviceToken, useSandbox = true, Json.obj(), None, config)
                .map { response =>
                  if response.body.contains("BadDeviceToken") then none
                  else if response.body.contains("InvalidProviderToken") then Some("InvalidProviderToken (Team ID / Bundle ID mismatch?)")
                  else Some(response.body)
                }

            def restoreRegistrations: F[Unit] = {
              storage.readAll
                .parEvalMap(5) { config =>
                  register(config).flatMap {
                    case Right(_)    =>
                      logger.info(s"restored config: ${config.bundleId}")
                    case Left(error) =>
                      logger.info(s"config not restored: ${config.bundleId} - ${error}")
                  }
                }
                .compile
                .drain
            }

            def validateConnection(ns: NightscoutConnection): F[Either[String, Unit]] = {
              registry.get
                .flatMap { registry =>
                  F.delay { registry.get(ns.bundleId) }
                }
                .flatMap {
                  case Some(_) => ().asRight[String].pure[F]
                  case None    => "bundle ID is not registered".asLeft[Unit].pure[F]
                }
            }

            def register(config: ApnsConfig): F[Either[String, Unit]] = {
              validate(config).flatMap {
                case None        =>
                  storage.save(config) *>
                    registry.update(_.updated(config.bundleId, config)) *>
                    ().asRight.pure[F]
                case Some(error) =>
                  s"invalid config: ${error}".asLeft[Unit].pure[F]
              }
            }

            def unregister(config: ApnsConfig): F[Either[String, Unit]] = {
              registry
                .modify { registry =>
                  registry.get(config.bundleId).filter(_ == config) match {
                    case Some(_) => (registry.removed(config.bundleId), config.bundleId.some)
                    case None    => (registry, none)
                  }
                }.flatMap {
                  case Some(bundleId) =>
                    storage.delete(config).as(().asRight)
                  case None           =>
                    "key not found".asLeft[Unit].pure[F]
                }

            }

            def cgmSilentPayload(sinceIso: Instant, sgv: Int): io.circe.Json =
              Json.obj(
                "aps"   -> Json.obj("content-available" -> Json.fromInt(1)),
                "type"  -> Json.fromString("cgm"),
                "since" -> Json.fromString(sinceIso.toString),
                "sgv"   -> Json.fromInt(sgv),
              )

            def notify(
              deviceToken: String,
              bundleId: String,
              useSandbox: Boolean,
              latestTimestamp: Instant,
              sgv: Int
            ): F[Unit] = {
              registry.get
                .flatMap { registry =>
                  F.delay { registry.get(bundleId) }
                }
                .flatMap {
                  case None         =>
                    logger.info(s"no apns config found for ${bundleId}")
                  case Some(config) =>
                    client
                      .sendBackgroundPush(
                        bundleId,
                        deviceToken,
                        useSandbox,
                        cgmSilentPayload(latestTimestamp, sgv),
                        collapseId = "sgv".some,
                        config
                      )
                      .attempt
                      .flatTap { result =>
                        logger.info(s"${deviceToken}: APNS push result: $result")
                      }
                      .void
                }
            }
          }
        }
      }

  }

}
