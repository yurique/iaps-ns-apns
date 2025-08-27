package iaps.nsapns

import cats.syntax.all.*
import cats.effect.Async
import cats.effect.Clock

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import pdi.jwt.JwtAlgorithm
import pdi.jwt.JwtCirce
import pdi.jwt.JwtClaim
import pdi.jwt.JwtHeader

object ApnsJwt {

  def loadECPrivateKeyFromP8[F[_]](pem: String)(using F: Async[F]): F[PrivateKey] =
    F.delay {
      val base64 = pem
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replaceAll("\\s", "")
      val spec   = new PKCS8EncodedKeySpec(Base64.getDecoder.decode(base64))
      KeyFactory.getInstance("EC").generatePrivate(spec)
    }

  def makeJwt[F[_]](
    privateKey: PrivateKey,
    teamId: String,
    keyId: String,
  )(using F: Async[F], clock: Clock[F]): F[String] =
    clock.realTimeInstant.flatMap { now =>
      F.delay {
        val header = JwtHeader(
          algorithm = Some(JwtAlgorithm.ES256),
          typ = Some("JWT"),
          keyId = Some(keyId)
        )

        val claim = JwtClaim(
          issuer = Some(teamId),
          issuedAt = Some(now.getEpochSecond)
        )

        JwtCirce.encode(header, claim, privateKey)
      }
    }

}
