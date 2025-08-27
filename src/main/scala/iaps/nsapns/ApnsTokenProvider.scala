package iaps.nsapns

import cats.effect.*
import cats.syntax.all.*
import java.time.Instant

trait ApnsTokenProvider[F[_]] {

  def token(config: ApnsConfig): F[String]

}

object ApnsTokenProvider {

  def apply[F[_]](using F: Async[F], clock: Clock[F]): F[ApnsTokenProvider[F]] = {
    F.ref(
      Map.empty[String, (Instant, String)]
    ).flatMap { cache =>
        F.delay {
          new ApnsTokenProvider[F] {

            def token(config: ApnsConfig): F[String] = {
              clock.realTimeInstant.flatMap { now =>
                cache.get
                  .flatMap { cache =>
                    F.delay {
                      cache.get(config.bundleId)
                    }
                  }
                  .flatMap {
                    case Some((createdAt, token)) if createdAt.isAfter(now.minusSeconds(50 * 60)) => token.pure[F]

                    case _ =>
                      ApnsJwt.loadECPrivateKeyFromP8(config.p8Pem).flatMap { privateKey =>
                        ApnsJwt.makeJwt(privateKey, config.teamId, config.keyId).flatTap { jwt =>
                          cache.update(_.updated(config.bundleId, (now, jwt)))
                        }
                      }
                  }
              }
            }

          }
        }
      }

  }

}
