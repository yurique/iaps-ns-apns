package iaps.nsapns

import cats.syntax.all.*
import cats.effect.*
import org.http4s.*
import org.http4s.client.*
import org.http4s.implicits.*
import org.typelevel.ci.*
import io.circe.Json

import scala.util.chaining.*

trait ApnsClient[F[_]] {

  def sendBackgroundPush(
    bundleId: String,
    deviceToken: String,
    useSandbox: Boolean,
    payload: Json,
    collapseId: Option[String] = Some("cgm"),
    config: ApnsConfig,
  ): F[ApnsResponse]

}

object ApnsClient {

  def apply[F[_]](
    client: Client[F],
    tokenProvider: ApnsTokenProvider[F]
  )(using F: Async[F]): F[ApnsClient[F]] = F.delay {
    new ApnsClient[F] {

      private val sandboxUrl = uri"https://api.sandbox.push.apple.com"
      private val prodUrl    = uri"https://api.push.apple.com"

      def sendBackgroundPush(
        bundleId: String,
        deviceToken: String,
        useSandbox: Boolean,
        payload: Json,
        collapseId: Option[String],
        config: ApnsConfig,
      ): F[ApnsResponse] =
        for {
          host <- F.delay { if useSandbox then sandboxUrl else prodUrl }
          jwt  <- tokenProvider.token(config)
          req   = Request[F](
                    method = Method.POST,
                    uri = host / "3" / "device" / deviceToken
                  )
                    .withEntity(payload.noSpaces)
                    .putHeaders(
                      Header.Raw(ci"authorization", s"bearer $jwt"),
                      Header.Raw(ci"apns-topic", bundleId),
                      Header.Raw(ci"apns-push-type", "background"),
                      Header.Raw(ci"apns-priority", "5"),
                      Header.Raw(ci"content-type", "application/json"),
                    )
                    .pipe { req =>
                      collapseId.fold(req) { collapseId =>
                        req.putHeaders(
                          Headers(Header.Raw(ci"apns-collapse-id", collapseId))
                        )
                      }
                    }
          res  <- client.run(req).use { r =>
                    r.as[String].map { body =>
                      val apnsId = r.headers
                        .get(CIString("apns-id"))
                        .map(_.head.value)
                      ApnsResponse(r.status, body, apnsId)
                    }
                  }
        } yield res

    }
  }

}
