package iaps.nsapns

import cats.syntax.all.*
import cats.effect.Async
import com.rewardsnetwork.pureaws.s3.PureS3Client
import fs2.Chunk
import fs2.io.file.Files
import fs2.io.file.Path
import io.circe.Decoder
import io.circe.Encoder
import io.circe.syntax.*
import io.circe.parser.*
import org.typelevel.log4cats.Logger
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.PutObjectRequest

import scala.util.chaining.*
import scala.jdk.CollectionConverters.*
import java.net.URI
import java.nio.ByteBuffer

trait JsonStorage[F[_], E] {

  def readAll: fs2.Stream[F, E]
  def save(e: E): F[Unit]
  def load(key: String): F[Option[E]]
  def delete(e: E): F[Unit]

}

object JsonStorage {

  def create[F[_], E: {Encoder, Decoder}](
    storage: StorageConfig,
    storagePath: String,
    getId: E => String
  )(using F: Async[F], files: Files[F]): JsonStorage[F, E] =
    storage match {
      case StorageConfig.FileSystem(storageRoot) => createFileSystem(storageRoot, storagePath, getId)
      case s: StorageConfig.S3                   => createS3(s, storagePath, getId)
    }

  private def createFileSystem[F[_], E: {Encoder, Decoder}](
    storageRoot: Path,
    storagePath: String,
    getId: E => String
  )(using F: Async[F], files: Files[F]): JsonStorage[F, E] =
    new JsonStorage[F, E] {

      private val storageDir = storageRoot.resolve(storagePath)

      private val logger: Logger[F] = org.typelevel.log4cats.slf4j.Slf4jLogger.getLogger[F]

      def readAll: fs2.Stream[F, E] =
        fs2.Stream.eval(files.isDirectory(storageDir)).flatMap { isDir =>
          if !isDir then fs2.Stream.empty
          else
            files
              .list(storageDir)
              .evalFilter { file => files.isRegularFile(file) }
              .filter { file => file.extName == ".json" }
              .evalMapFilter { file =>
                files.readAll(file).through(fs2.text.utf8.decode).compile.string.flatMap { string =>
                  F.delay(decode[E](string)).flatMap {
                    case Right(entity) => entity.some.pure[F]
                    case Left(error)   =>
                      logger.warn(s"failed to decode $file: ${error.show}").as(none)
                  }
                }
              }
        }

      def save(e: E): F[Unit] =
        files.createDirectories(storageDir) *>
          fs2.Stream
            .emit(e.asJson.noSpaces).through(fs2.text.utf8.encode)
            .through(files.writeAll(storageDir.resolve(s"${getId(e)}.json")))
            .compile.drain

      def load(key: String): F[Option[E]] = {
        val file = storageDir.resolve(s"${key}.json")
        files.isRegularFile(file).flatMap {
          case false => none[E].pure[F]
          case true  =>
            files.readAll(file).through(fs2.text.utf8.decode).compile.string.flatMap { string =>
              F.delay(decode[E](string)).flatMap {
                case Right(entity) => entity.some.pure[F]
                case Left(error)   =>
                  logger.warn(s"failed to decode $file: ${error.show}").as(none)
              }
            }
        }
      }

      def delete(e: E): F[Unit] = {
        val file = storageDir.resolve(s"${getId(e)}.json")
        files.isRegularFile(file).flatMap {
          case false => F.unit
          case true  => files.delete(file).attempt.void
        }
      }

    }

  private def createS3[F[_], E: {Encoder, Decoder}](
    storage: StorageConfig.S3,
    storagePath: String,
    getId: E => String
  )(using F: Async[F]): JsonStorage[F, E] =
    new JsonStorage[F, E] {

      private val logger: Logger[F] = org.typelevel.log4cats.slf4j.Slf4jLogger.getLogger[F]
      private val asyncS3Client     = S3AsyncClient
        .builder()
        .pipe { b => storage.endpoint.map(new URI(_)).fold(b)(b.endpointOverride) }
        .region(Region.of(storage.region))
        .pipe { b => storage.forcePathStyle.fold(b)(force => b.forcePathStyle(force)) }
        .credentialsProvider(
          storage.credentials match {
            case Some(credentials) => StaticCredentialsProvider.create(credentials)
            case None              => DefaultCredentialsProvider.builder().build()
          }
        )
        .build()

      private val s3Client = PureS3Client[F](asyncS3Client)

      def readAll: fs2.Stream[F, E] =
        fs2.Stream
          .unfoldChunkLoopEval(none[String]) { continuationToken =>
            val request = ListObjectsV2Request
              .builder()
              .bucket(storage.bucket)
              .prefix(s"${storage.path}/${storagePath}/")
              .pipe { b => continuationToken.fold(b)(b.continuationToken) }
              .maxKeys(500).build()

            s3Client.listObjects(request).flatMap { response =>
              F.delay {
                (
                  Option(response.contents()).map(list => Chunk.array(list.asScala.toArray)).getOrElse(Chunk.empty),
                  Option(response.nextContinuationToken()).map(_.some)
                )
              }
            }
          }
          .evalFilter { file =>
            F.delay { file.key().endsWith(".json") }
          }
          .evalMapFilter { file =>
            s3Client
              .getObjectStream(
                GetObjectRequest.builder().bucket(storage.bucket).key(file.key()).build()
              )
              .through(fs2.text.utf8.decode).compile.string.flatMap { string =>
                F.delay(decode[E](string)).flatMap {
                  case Right(entity) => entity.some.pure[F]
                  case Left(error)   =>
                    logger.warn(s"failed to decode $file: ${error.show}").as(none)
                }
              }
          }

      def save(e: E): F[Unit] =
        fs2.Stream
          .emit(e.asJson.noSpaces)
          .covary[F]
          .through(fs2.text.utf8.encode)
          .compile
          .to(Array)
          .map(ByteBuffer.wrap)
          .flatMap { bytes =>
            s3Client.putObject(
              PutObjectRequest.builder().bucket(storage.bucket).key(s"${storage.path}/${storagePath}/${getId(e)}.json").build(),
              bytes
            )
          }
          .void

      def load(key: String): F[Option[E]] =
        s3Client
          .getObjectStream(
            GetObjectRequest.builder().bucket(storage.bucket).key(s"${storage.path}/${storagePath}/${key}.json").build()
          )
          .through(fs2.text.utf8.decode).compile.string.flatMap { string =>
            F.delay(decode[E](string)).flatMap {
              case Right(entity) => entity.some.pure[F]
              case Left(error)   =>
                logger.warn(s"failed to decode $key: ${error.show}").as(none)
            }
          }

      def delete(e: E): F[Unit] =
        s3Client
          .deleteObject(
            DeleteObjectRequest.builder().bucket(storage.bucket).key(s"${storage.path}/${storagePath}/${getId(e)}.json").build()
          )
          .void

    }

}
