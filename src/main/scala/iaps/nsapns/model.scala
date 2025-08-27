package iaps.nsapns

import cats.syntax.all.*
import fs2.io.file.Path
import io.circe.Decoder
import io.circe.Encoder
import org.http4s.Status

given Encoder[org.http4s.Uri] = Encoder.encodeString.contramap(_.renderString)
given Decoder[org.http4s.Uri] = Decoder.decodeString.emap(s => org.http4s.Uri.fromString(s).leftMap(_.sanitized))

enum StorageConfig {

  case FileSystem(storagePath: Path)
  case S3(
    region: String,
    endpoint: Option[String],
    bucket: String,
    path: String,
    accessKey: String,
    secretKey: String
  )

}

case class ServerConfig(
  storage: StorageConfig,
  registryToken: Option[String]
)

case class ApnsConfig(
  teamId: String,
  bundleId: String,
  keyId: String,
  p8Pem: String,
) derives Encoder,
      Decoder

case class ApnsResponse(
  status: Status,
  body: String,
  apnsId: Option[String]
)

case class NightscoutConnection(
  deviceToken: String,
  nsBase: org.http4s.Uri,
  apiSecretSha1: String,
  bundleId: String,
  useSandbox: Boolean
) derives Encoder,
      Decoder

case class SubscribeResponse(
  okay: Boolean,
  message: Option[String],
  error: Option[String]
) derives Encoder

case class NsEntry(
  sgv: Option[Int],
  direction: Option[String],
  date: Long,
  dateString: Option[String]
) derives Decoder

object KeyId {

  private val KeyFileName = raw"AuthKey_(\w+)\.p8".r

  def unapply(s: String): Option[String] = {
    s match {
      case KeyFileName(keyId) => Some(keyId)
      case _                  => None
    }

  }

}
