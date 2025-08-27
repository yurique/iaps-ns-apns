package iaps.nsapns

import cats.syntax.all.*
import cats.effect.*
import org.typelevel.log4cats.Logger
import org.http4s.ember.client.*
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import fs2.io.file.Path
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials

object Boot
    extends CommandIOApp(
      name = "iaps-ns-apns-bridge",
      header = "iAPS Nightscout-APNS Bridge",
    ) {

  private val fileSystemStorageOpts =
    Opts.env[String]("NSAPNS_STORAGE_PATH", "path to a directory to be used for persistence").map { path =>
      StorageConfig.FileSystem(Path(path))
    }

  private val s3AuthOpts =
    (
      Opts.env[String]("NSAPNS_S3_ACCESS_KEY", "S3 key"),
      Opts.env[String]("NSAPNS_S3_SECRET_KEY", "S3 key"),
    ).mapN { (accessKey, secretKey) =>
      AwsBasicCredentials
        .builder()
        .accessKeyId(accessKey)
        .secretAccessKey(secretKey)
        .build()
    }

  private val spacesStorageOpts =
    (
      Opts.env[String]("NSAPNS_S3_REGION", "S3 region"),
      Opts.env[String]("NSAPNS_S3_ENDPOINT", "S3 endpoint").orNone,
      Opts.env[String]("NSAPNS_S3_BUCKET", "S3 bucket"),
      Opts.env[String]("NSAPNS_S3_FORCE_PATH_STYLE", "S3 force path style (optional)").orNone.map { s =>
        s.map(_.toLowerCase).map(Set("yes", "true", "on").contains)
      },
      Opts.env[String]("NSAPNS_S3_ROOT_PATH", "S3 path within the bucket"),
      s3AuthOpts.orNone
    ).mapN { (region, endpoint, bucket, forcePathStyle, path, credentials) =>
      StorageConfig.S3(
        region = region,
        endpoint = endpoint,
        bucket = bucket,
        forcePathStyle = forcePathStyle,
        path = path,
        credentials = credentials
      )
    }

  private val serverOptions =
    (
      Opts.env[String]("NSAPNS_REGISTRY_TOKEN", "(optional) token to restrict private key registration)").orNone,
      fileSystemStorageOpts.orElse(spacesStorageOpts)
    ).tupled

  override def main: Opts[IO[ExitCode]] = {
    serverOptions
      .map { case (registryToken, storage) =>
        startServer(ServerConfig(storage, registryToken))
      }
      .map(_.onError { e =>
        org.typelevel.log4cats.slf4j.Slf4jLogger.getLogger[IO].error(e)("error in the app")
      })
  }

  val logger: Logger[IO] = org.typelevel.log4cats.slf4j.Slf4jLogger.getLogger[IO]

  private def startServer(
    serverConfig: ServerConfig,
  ): IO[ExitCode] = {
    val app =
      for {
        client        <- EmberClientBuilder.default[IO].withHttp2.build
        tokenProvider <- ApnsTokenProvider[IO].toResource
        apnsClient    <- ApnsClient[IO](client, tokenProvider).toResource
        apns          <- ApnsManager[IO](serverConfig, apnsClient).toResource
        _             <- apns.restoreRegistrations.toResource
        poller        <- NightscoutPoller(serverConfig, client, apns)
        _             <- poller.restoreAll.toResource
        _             <- ApiServer.create(serverConfig, apns, poller)
      } yield ExitCode.Success

    app.useForever
  }

}
