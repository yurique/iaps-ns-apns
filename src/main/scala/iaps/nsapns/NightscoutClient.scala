package iaps.nsapns

import cats.effect.*
import cats.syntax.all.*
import io.circe.parser.*
import org.http4s.*
import org.http4s.client.*
import org.typelevel.ci.*

import scala.concurrent.duration.*

trait NightscoutClient[F[_]] {

  def fetchEntriesSince(
    since: FiniteDuration,
    limit: Int = 1
  ): F[List[NsEntry]]

}

object NightscoutClient {

  def apply[F[_]: Async](
    client: Client[F],
    ns: NightscoutConnection,
  ): NightscoutClient[F] = new NightscoutClient[F] {
    def fetchEntriesSince(since: FiniteDuration, limit: Int): F[List[NsEntry]] = {

      // /api/v1/entries.json?find[date][$gt]=<ms>&count=<n>
      val base      = ns.nsBase / "api" / "v1" / "entries" / "sgv.json"
      val withQuery = base
        .withQueryParam(s"find[date][$$gt]", since.toMillis.toString)
        .withQueryParam("count", limit.toString)

      val req = Request[F](Method.GET, withQuery)
        .putHeaders(Header.Raw(ci"accept", "application/json"))
        .putHeaders(Header.Raw(ci"api-secret", ns.apiSecretSha1))

      client.run(req).use { resp =>
        if resp.status.isSuccess then {
          resp.as[String].flatMap { body =>
            decode[List[NsEntry]](body) match {
              case Right(entries) => Async[F].pure(entries)
              case Left(err)      => Async[F].raiseError(new RuntimeException(s"Decode failed: $err\n$body"))
            }
          }
        } else {
          resp.as[String].flatMap { b =>
            Async[F].raiseError(new RuntimeException(s"Nightscout ${resp.status.code}: $b"))
          }
        }
      }
    }
  }

}
