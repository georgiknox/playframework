package play.sbt.activator

import sbt._
import sbt.Keys._
import com.typesafe.sbt.S3Plugin._

object Templates {

  val templates = SettingKey[Seq[File]]("activator-templates")
  val templateParameters = SettingKey[Map[String, String]]("template-parameters")
  val gitHash = TaskKey[String]("git-hash")

  val prepareTemplates = TaskKey[Seq[File]]("prepare-templates")
  val testTemplates = TaskKey[Unit]("test-templates")
  val zipTemplates = TaskKey[Seq[File]]("zip-templates")
  val publishTemplatesTo = SettingKey[String]("publish-templates-to")
  val doPublishTemplates = TaskKey[Boolean]("do-publish-templates")
  val publishTemplates = TaskKey[Unit]("publish-templates")

  val templateSettings: Seq[Setting[_]] = s3Settings ++ Seq(
    templates := Nil,
    templateParameters := Map.empty,

    prepareTemplates := {
      streams.value.log.info("Preparing templates...")
      val templateDirs = templates.value
      val params = templateParameters.value
      val outDir = target.value / "templates"
      templateDirs.map { template =>
        val destDir = outDir / template.getName
        val rebaser = rebase(template, destDir)
       
        // First delete the destination directory
        IO.delete(destDir)

        def subParams(files: Seq[File]): Unit = {
          files.headOption match {
            case Some(dir) if dir.isDirectory => subParams(files.tail ++ dir.listFiles())
            case Some(file) =>
              val contents = IO.read(file)
              val newContents = params.foldLeft(contents) { (str, param) =>
                str.replace("%" + param._1 + "%", param._2)
              }
              val dest = rebaser(file).getOrElse(throw new RuntimeException("???"))
              IO.write(dest, newContents)
              subParams(files.tail)
            case None => ()
          }
        }
        subParams(template.listFiles())

        destDir
      }
    },

    testTemplates := {
      val preparedTemplates = prepareTemplates.value
      val testDir = target.value / "template-tests"
      val build = (baseDirectory.value.getParentFile / "framework" / "build").getCanonicalPath
      preparedTemplates.foreach { template =>
        val templateDir = testDir / template.getName
        IO.delete(templateDir)
        IO.copyDirectory(template, templateDir)
        streams.value.log.info("Testing template: " + template.getName)
        @volatile var out = List.empty[String]
        val rc = Process(build + " test", templateDir).!(StdOutLogger { s => out = s :: out })
        if (rc != 0) {
          out.reverse.foreach(println)
          streams.value.log.error("Template " + template.getName + " failed to build")
          throw new TemplateBuildFailed(template.getName)
        }
      }
    },

    zipTemplates := {
      streams.value.log.info("Packaging templates...")
      val preparedTemplates = prepareTemplates.value
      val distDir = target.value / "template-dist"
      preparedTemplates.map { template =>
        val zipFile = distDir / (template.getName + ".zip")
        val files = template.***.filter(!_.isDirectory) x relativeTo(template)
        IO.zip(files, zipFile)
        zipFile
      }
    },

    gitHash := "git rev-parse HEAD".!!.trim,

    S3.host in S3.upload := "downloads.typesafe.com.s3.amazonaws.com",
    S3.progress in S3.upload := true,
    mappings in S3.upload := {
      streams.value.log.info("Uploading templates to S3...")
      val zippedTemplates = zipTemplates.value
      val templateDir = s"play/templates/${gitHash.value}/"
      zippedTemplates.map { template =>
        (template, templateDir + template.getName)
      }
    },
    S3.host in S3.delete := "downloads.typesafe.com.s3.amazonaws.com",
    S3.keys in S3.delete := {
      val templateDirs = templates.value
      val templateDir = s"play/templates/${gitHash.value}/"
      templateDirs.map { template =>
        s"$templateDir${template.getName}.zip"
      }
    },

    publishTemplatesTo := "typesafe.com",
    doPublishTemplates := {
      val host = publishTemplatesTo.value
      val creds = Credentials.forHost(credentials.value, host).getOrElse {
        sys.error("Could not find credentials for host: " + host)
      }
      val upload = S3.upload.value // Ignore result
      val uploaded = (mappings in S3.upload).value.map(m => m._1.getName -> m._2)
      val logger = streams.value.log

      logger.info("Publishing templates...")

      import play.api.libs.ws._
      import play.api.libs.ws.ning.NingWSClient
      import play.api.libs.json._
      import com.ning.http.client.AsyncHttpClientConfig.Builder
      import java.util.Timer
      import java.util.TimerTask
      import scala.concurrent.duration._
      import scala.concurrent._
      import scala.concurrent.ExecutionContext.Implicits.global

      val timer = new Timer()
      val client = new NingWSClient(new Builder().build())
      try {

        def clientCall(path: String): WSRequestHolder = client.url(s"https://$host$path")
          .withAuth(creds.userName, creds.passwd, WSAuthScheme.BASIC)

        def timeout(duration: FiniteDuration): Future[Unit] = {
          val promise = Promise[Unit]()
          timer.schedule(new TimerTask() {
            def run = promise.success(())
          }, duration.toMillis)
          promise.future
        }
       
        def waitUntilNotPending(uuid: String, statusUrl: String): Future[Either[String, String]] = {
          val status: Future[TemplateStatus] = for {
            _ <- timeout(2.seconds)
            resp <- clientCall(statusUrl).withHeaders("Accept" -> "application/json,text/html;q=0.9").get()
          } yield {
            resp.header("Content-Type") match {
              case Some(json) if json.startsWith("application/json") =>
                val js = resp.json
                (js \ "status").as[String] match {
                  case "pending" => TemplatePending(uuid)
                  case "validated" => TemplateValidated(uuid)
                  case "failed" => TemplateFailed(uuid, (js \ "errors").as[Seq[String]])
                }
              case _ =>
                val body = resp.body
                body match {
                  case pending if body.contains("This template is being processed.") => TemplatePending(uuid)
                  case validated if body.contains("This template was published successfully!") => TemplateValidated(uuid)
                  case failed if body.contains("This template failed to publish.") =>
                    TemplateFailed(uuid, extractErrors(body))
                }
            }
          }

          status.flatMap {
            case TemplatePending(uuid) => waitUntilNotPending(uuid, statusUrl)
            case TemplateValidated(_) => Future.successful(Right(uuid))
            case TemplateFailed(_, errors) => Future.successful(Left(errors.mkString("\n")))
          }
        }

        val futures: Seq[Future[(String, String, Either[String, String])]] = uploaded.map {
          case (name, key) =>
            clientCall("/activator/template/publish")
              .post(s"url=http://downloads.typesafe.com/$key").flatMap { resp =>
                if (resp.status != 200) {
                  logger.error("Error publishing template " + name)
                  logger.error("Status code was: " + resp.status)
                  logger.error("Body was: " + resp.body)
                  throw new RuntimeException("Error publishing template")
                }
                val js = resp.json
                val uuid = (js \ "uuid").as[String]
                val statusUrl = (for {
                  links <- (js \ "_links").asOpt[JsObject]
                  status <- (links \ "activator/templates/status").asOpt[JsObject]
                  url <- (status \ "href").asOpt[String]
                } yield url).getOrElse(s"/activator/template/status/$uuid")
                waitUntilNotPending(uuid, statusUrl)
              }.map(result => (name, key, result))
        }

        val results = Await.result(Future.sequence(futures), 1.hour)

        results.foldLeft(true) { (overall, result) =>
          result match {
            case (name, key, Left(error)) =>
              logger.error("Error publishing template " + name)
              logger.error(error)
              false
            case (name, key, Right(uuid)) =>
              logger.info("Template " + name + " published successfully with uuid: " + uuid)
              overall
          }
        }
      } finally {
        timer.cancel()
        client.close()
      }

    },

    publishTemplates <<= (doPublishTemplates, S3.delete, streams).apply { (dpt, s3delete, s) =>
      for {
        streams <- s
        result <- dpt
        _ <- {
          streams.log.info("Cleaning up S3...") 
          s3delete
        }
      } yield result match {
        case true => ()
        case false => throw new TemplatePublishFailed
      }
    }

  )

  private class TemplateBuildFailed(template: String) extends RuntimeException(template) with FeedbackProvidedException
  private class TemplatePublishFailed extends RuntimeException with FeedbackProvidedException

  private object StdOutLogger {
    def apply(log: String => Unit) = new ProcessLogger {
      def info(s: => String) = log(s)
      def error(s: => String) = System.err.println(s)
      def buffer[T](f: => T) = f
    }
  }

  private sealed trait TemplateStatus
  private case class TemplateValidated(uuid: String) extends TemplateStatus
  private case class TemplateFailed(uuid: String, errors: Seq[String]) extends TemplateStatus
  private case class TemplatePending(uuid: String) extends TemplateStatus

  private def extractErrors(body: String) = body.split("\n")
    .dropWhile(!_.contains("This template failed to publish."))
    .drop(1)
    .takeWhile(!_.contains("</article>"))
    .map(_.trim)
    .filterNot(_.isEmpty)
    .map(_.replaceAll("<p>", "").replaceAll("</p>", ""))
}
