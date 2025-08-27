package iaps.nsapns

import cats.data.OptionT
import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.Chunk
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.*
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.http4s.multipart.Multipart
import org.http4s.server.Router
import org.http4s.server.middleware.*

import java.nio.charset.StandardCharsets
import scala.io.Source
import scala.util.Using
import scala.util.chaining.*

object ApiServer {

  def create(
    serverConfig: ServerConfig,
    apns: ApnsManager[IO],
    nightscout: NightscoutPoller[IO],
  )(using F: Async[IO]): Resource[IO, server.Server] = {

    val cssResource = Chunk.array(Using(Source.fromResource("main.css"))(_.mkString).toOption.getOrElse("").getBytes(StandardCharsets.UTF_8))

    def handleRegistryTokenField(s: String): String =
      if serverConfig.registryToken.isEmpty then
        s
          .replace("%%%REGISTRY_TOKEN_DISABLED%%%", "true")
          .replace("%%%REGISTRY_TOKEN_PLACEHOLDER%%%", "not required")
      else
        s
          .replace("%%%REGISTRY_TOKEN_DISABLED%%%", "false")
          .replace("%%%REGISTRY_TOKEN_PLACEHOLDER%%%", "registry token is required on this server")

    val registerHtmlResource   = Chunk.array(
      Using(Source.fromResource("key-register.html"))(s => handleRegistryTokenField(s.mkString)).toOption.getOrElse("").getBytes(StandardCharsets.UTF_8)
    )
    val unregisterHtmlResource = Chunk.array(
      Using(Source.fromResource("key-unregister.html"))(s => handleRegistryTokenField(s.mkString)).toOption.getOrElse("").getBytes(StandardCharsets.UTF_8)
    )

    val service = HttpRoutes.of[IO] {
      case req @ GET -> Root / "main.css" =>
        Ok.apply(
          fs2.Stream.chunk(cssResource).covary[IO],
          Headers(`Content-Type`(MediaType.text.css))
        )

      case req @ GET -> Root / "key-registration" =>
        Ok.apply(
          fs2.Stream.chunk(registerHtmlResource).covary[IO],
          Headers(`Content-Type`(MediaType.text.html))
        )

      case req @ GET -> Root / "key-removal" =>
        Ok.apply(
          fs2.Stream.chunk(unregisterHtmlResource).covary[IO],
          Headers(`Content-Type`(MediaType.text.html))
        )

      case req @ POST -> Root / "api" / "v1" / "p8" / "register" =>
        req.as[Multipart[IO]].flatMap { formData =>
          parseFormData(formData).flatMap {
            case Left(error)            => BadRequest(error)
            case Right((config, token)) =>
              if serverConfig.registryToken.forall(token.contains) then
                apns.register(config).flatMap {
                  case Right(_)    =>
                    Ok("key registered")
                  case Left(error) =>
                    BadRequest(error)
                }
              else Forbidden("invalid registry token")
          }
        }

      case req @ POST -> Root / "api" / "v1" / "p8" / "unregister" =>
        req.as[Multipart[IO]].flatMap { formData =>
          parseFormData(formData).flatMap {
            case Left(error)            => BadRequest(error)
            case Right((config, token)) =>
              if serverConfig.registryToken.forall(token.contains) then
                apns.unregister(config).flatMap {
                  case Right(_)    =>
                    Ok("key un-registered")
                  case Left(error) =>
                    BadRequest(error)
                }
              else Forbidden("invalid registry token")
          }
        }

      case req @ POST -> Root / "api" / "v1" / "ns" / "subscribe"   =>
        for {
          ns   <- req.as[NightscoutConnection]
          resp <- nightscout.start(ns).flatMap { result =>
                    Ok(
                      SubscribeResponse(
                        okay = result.isRight,
                        message = result.toOption,
                        error = result.left.toOption
                      )
                    )
                  }
        } yield resp
      case req @ POST -> Root / "api" / "v1" / "ns" / "unsubscribe" =>
        for {
          ns   <- req.as[NightscoutConnection]
          resp <- nightscout.stop(ns).flatMap { _ =>
                    Ok(
                      SubscribeResponse(
                        okay = true,
                        message = none,
                        error = none
                      )
                    )
                  }
        } yield resp
    }

    val httpApp = Router("/" -> service).orNotFound
      .pipe(
        RequestLogger.httpApp(logHeaders = false, logBody = false)
      )
      .pipe(
        ResponseLogger.httpApp(logHeaders = false, logBody = false)
      )

    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"22626")
      .withHttpApp(httpApp)
      .build

  }

  private def parseFormData(formData: Multipart[IO]): IO[Either[String, (ApnsConfig, Option[String])]] = {
    val parts         = formData.parts
    val upload        = parts.find(_.name.contains("upload"))
    val teamId        = parts.find(_.name.contains("teamId"))
    val bundleId      = parts.find(_.name.contains("bundleId"))
    val registryToken = parts.find(_.name.contains("registryToken"))

    (upload, teamId, bundleId).tupled match {
      case Some((upload, teamId, bundleId)) =>
        upload.filename.collect { case KeyId(keyId) => keyId } match {
          case None        => "invalid key file name, expected: AuthKey_XXXXXXXXXX.p8".asLeft[(ApnsConfig, Option[String])].pure[IO]
          case Some(keyId) =>
            for {
              teamId        <- teamId.body.through(fs2.text.utf8.decode).compile.string
              bundleId      <- bundleId.body.through(fs2.text.utf8.decode).compile.string
              key           <- upload.body.through(fs2.text.utf8.decode).compile.string
              registryToken <- OptionT.fromOption[IO](registryToken).semiflatMap(_.body.through(fs2.text.utf8.decode).compile.string).value
              result        <- IO {
                                 (ApnsConfig(
                                    teamId = teamId,
                                    keyId = keyId,
                                    bundleId = bundleId,
                                    p8Pem = key,
                                  ),
                                  registryToken
                                 ).asRight[String]
                               }
            } yield result
        }

      case _ =>
        "missing data".asLeft[(ApnsConfig, Option[String])].pure[IO]
    }

  }

}
