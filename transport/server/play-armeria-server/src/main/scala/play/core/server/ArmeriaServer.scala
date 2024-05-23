/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.core.server

import com.linecorp.armeria.common.Http1HeaderNaming
import com.linecorp.armeria.common.logging.LogLevel
import com.linecorp.armeria.server.encoding.DecodingService
import com.linecorp.armeria.server.logging.{ContentPreviewingService, LoggingService}
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.Server as ArmeriaHttpServer
import com.typesafe.config.ConfigMemorySize
import io.netty.handler.ssl.ClientAuth
import org.apache.pekko.Done
import org.apache.pekko.actor.{ActorSystem, CoordinatedShutdown}
import org.apache.pekko.stream.Materializer

import java.net.InetSocketAddress
import play.api.{Application, BuiltInComponents, Configuration, Logger, Mode, NoHttpFiltersComponents, Play}
import play.api.http.HttpProtocol
import play.api.internal.libs.concurrent.CoordinatedShutdownSupport
import play.api.routing.Router
import play.core.ApplicationProvider
import play.core.server.ArmeriaServer.logger
import play.core.server.Server.ServerStoppedReason
import play.core.server.armeria.ArmeriaServerConfigurator
import play.core.server.armeria.PlayHttpService

import scala.collection.JavaConverters.*
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

class ArmeriaServer(
    config: ServerConfig,
    val applicationProvider: ApplicationProvider,
    stopHook: () => Future[_],
    val actorSystem: ActorSystem)
                   (implicit val materializer: Materializer) extends Server {

  private val serverConfig        = config.configuration.get[Configuration]("play.server")
  private val armeriaConfig     = serverConfig.get[Configuration]("armeria")
  private val maxContentLength    = Server.getPossiblyInfiniteBytes(serverConfig.underlying, "max-content-length")
  private val maxInitialLineLength = armeriaConfig.get[Int]("maxInitialLineLength")
  private val maxHeaderSize       = serverConfig.get[ConfigMemorySize]("max-header-size").toBytes.toInt
  private val httpsWantClientAuth = serverConfig.get[Boolean]("https.wantClientAuth")
  private val httpsNeedClientAuth = serverConfig.get[Boolean]("https.needClientAuth")
  private val httpIdleTimeout     = serverConfig.get[Duration]("http.idleTimeout")

  private val http1HeaderNaming = armeriaConfig.get[String]("http1HeaderNaming")
  if (http1HeaderNaming != "tradition" && http1HeaderNaming != "lowercase") {
    logger.warn(s"""Unexpected `play.server.armeria.http1HeaderNaming` [$http1HeaderNaming] is 
                   |specified. Only 'tradition' and 'lowercase' is supported.""".stripMargin)
  }

  private val wsBufferLimit       = serverConfig.get[ConfigMemorySize]("websocket.frame.maxLength").toBytes.toInt
  private val wsKeepAliveMode     = serverConfig.get[String]("websocket.periodic-keep-alive-mode")
  private val wsKeepAliveMaxIdle  = serverConfig.get[Duration]("websocket.periodic-keep-alive-max-idle")

  private val coordinatedShutdown = CoordinatedShutdown(actorSystem)

  private val server: ArmeriaHttpServer = buildServer()

  registerShutdownTasks()

  private def buildServer(): ArmeriaHttpServer = {

    val serverBuilder = ArmeriaHttpServer.builder()

    config.port.foreach { port =>
      serverBuilder.http(new InetSocketAddress(config.address, port))
    }
    config.sslPort.foreach { port =>
      serverBuilder.https(new InetSocketAddress(config.address, port))
    }
    serverBuilder.decorator(DecodingService.newDecorator())

    // TODO(ikhoon): Remove LoggingService after testing
    serverBuilder.decorator(
      LoggingService
        .builder()
        .newDecorator()
    )
    // TODO(ikhoon): Remove after test
    //    serverBuilder.verboseResponses(true)

    serverBuilder.maxRequestLength(maxContentLength)
    serverBuilder.http1MaxInitialLineLength(maxInitialLineLength)
    serverBuilder.http1MaxHeaderSize(maxHeaderSize)
    serverBuilder.http2MaxHeaderListSize(maxHeaderSize)
    serverBuilder.idleTimeoutMillis(httpIdleTimeout.toMillis)

    if (http1HeaderNaming == "tradition") {
      serverBuilder.http1HeaderNaming(Http1HeaderNaming.traditional())
    }

    configureGracefulShutdown(serverBuilder)

    // TODO(ikhoon): Customize TLS using configurations.
    serverBuilder.tlsCustomizer(customizer => {
      val clientAuth = if (httpsNeedClientAuth) {
        ClientAuth.REQUIRE
      } else if (httpsWantClientAuth) {
        ClientAuth.OPTIONAL
      } else {
        ClientAuth.NONE
      }
      customizer.clientAuth(clientAuth)
    })

    try {
      applicationProvider.get.map(application => {
        val configurator =
          try {
            application.injector.instanceOf[ArmeriaServerConfigurator]
          } catch {
            case NonFatal(_) => null // ignore silently
          }

        if (configurator != null) {
          // Customize serverBuilder with the user-defined configurator
          configurator.configure(serverBuilder)
        }
      })
    } catch {
      case NonFatal(ex) =>
        logger.warn(
          "Failed to load ApplicationProvider to inject ArmeriaServerConfigurator. " +
            "As ArmeriaServerConfigurator does not support auto-reloading, restart the application " +
            "after fixing the problem",
          ex
        )
    }

    val service = new PlayHttpService(this, wsBufferLimit, wsKeepAliveMode, wsKeepAliveMaxIdle)
    serverBuilder.serviceUnder("/", service)

    val armeriaServer = serverBuilder.build()
    armeriaServer.start().join()
    armeriaServer
  }

  /**
   * Sets the amount of time to wait after calling `Server.stop()` for
   * requests to go away before actually shutting down.
   */
  private def configureGracefulShutdown(serverBuilder: ServerBuilder): Unit = {
    val serviceUnboundTimeout = coordinatedShutdown.timeout(CoordinatedShutdown.PhaseServiceUnbind)
    val gracefulShutdownQuietPeriod =
      armeriaConfig.getOptional[FiniteDuration]("gracefulShutdownQuietPeriod").getOrElse(serviceUnboundTimeout)
    val gracefulShutdownTimeout =
      armeriaConfig.getOptional[FiniteDuration]("gracefulShutdownTimeout").getOrElse(serviceUnboundTimeout)
    // The termination hard-deadline is either what was configured by the user
    // or defaults to `service-unbind` phase timeout.
    if (gracefulShutdownTimeout > serviceUnboundTimeout) {
      logger.warn(
        s"""The value for `play.server.armeria.gracefulShutdownTimeout` [$gracefulShutdownTimeout] is higher 
           |than 
           |the total `service-unbind.timeout` duration [$serviceUnboundTimeout]. 
           |Set `akka.coordinated-shutdown.phases.service-unbind.timeout` to an equal (or greater) value 
           |to prevent unexpected server termination.""".stripMargin
      )
    }
    serverBuilder.gracefulShutdownTimeoutMillis(gracefulShutdownQuietPeriod.toMillis, gracefulShutdownTimeout.toMillis)
  }

  override def mode: Mode = config.mode

  override lazy val mainAddress: InetSocketAddress = server.activePort().localAddress()

  override lazy val serverEndpoints: ServerEndpoints = {
    val endpoints = server
      .activePorts()
      .asScala
      .flatMap {
        case (addr, port) =>
          // Armeria can handle multiple protocols in a single port.
          // For example, HTTP and HTTPS can be served at port 8080.
          val endpoints0 = mutable.Buffer[ServerEndpoint]()
          if (port.hasHttps) {
            endpoints0 += ServerEndpoint(
              description = "Armeria HTTPS/2.0 (encrypted)",
              scheme = "https",
              host = config.address,
              port = addr.getPort,
              protocols = Set(HttpProtocol.HTTP_1_0, HttpProtocol.HTTP_1_1, HttpProtocol.HTTP_2_0),
              serverAttribute = Some("Armeria server header"), // TODO(ikhoon): Integrate with configuration
              // Armeria internally creates Netty's SslContext which can't directly converted to
              // an JDK SSLContext. An SSLSession can be accessed from ServiceRequestContext.sslSession()
              ssl = None
            )
          }

          if (port.hasHttp) {
            endpoints0 += ServerEndpoint(
              description = "Armeria HTTP/2.0 (plaintext)",
              scheme = "http",
              host = config.address,
              port = addr.getPort,
              protocols = Set(HttpProtocol.HTTP_1_0, HttpProtocol.HTTP_1_1, HttpProtocol.HTTP_2_0),
              serverAttribute = Some("Armeria server header"), // TODO(ikhoon): Integrate with configuration
              ssl = None
            )
          }
          endpoints0
      }

    ServerEndpoints(endpoints.toSeq)
  }

  override def stop(): Unit = CoordinatedShutdownSupport.syncShutdown(actorSystem, ServerStoppedReason)

  // Using CoordinatedShutdown means that instead of invoking code imperatively in `stop`
  // we have to register it as early as possible as CoordinatedShutdown tasks and
  // then `stop` runs CoordinatedShutdown.
  private def registerShutdownTasks(): Unit = {
    implicit val ctx: ExecutionContext = actorSystem.dispatcher

    val coordinatedShutdown = CoordinatedShutdown(actorSystem)
    coordinatedShutdown.addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "trace-server-stop-request") { () =>
      mode match {
        case Mode.Test =>
        case _         => logger.info("Stopping Armeria server...")
      }
      Future.successful(Done)
    }

    coordinatedShutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "armeria-server-unbind") { () =>
      // TODO(ikhoon): Revert before sending a PR
//      server.stop().asScala.map(_ => Done)
      server.stop()
      Future.successful(Done)
    }

    // Call provided hook
    // Do this last because the hooks were created before the server,
    // so the server might need them to run until the last moment.
    coordinatedShutdown.addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "user-provided-server-stop-hook") {
      () =>
        logger.info("Running provided shutdown stop hooks")
        stopHook().map(_ => Done)
    }
    coordinatedShutdown.addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "shutdown-logger") { () =>
      Future {
        super.stop()
        Done
      }
    }
  }
}

/**
 * The Armeria server provider.
 */
class ArmeriaServerProvider extends ServerProvider {

  override def createServer(context: ServerProvider.Context): ArmeriaServer = {
    new ArmeriaServer(context.config, context.appProvider, context.stopHook, context.actorSystem)(context.materializer)
  }
}

object ArmeriaServer extends ServerFromRouter {

  private val logger = Logger(classOf[ArmeriaServer])

  implicit val provider: ArmeriaServerProvider = new ArmeriaServerProvider

  protected override def createServerFromRouter(serverConf: ServerConfig)(
      routes: ServerComponents with BuiltInComponents => Router
  ): Server = {
    new ArmeriaServerComponents with BuiltInComponents with NoHttpFiltersComponents {
      override lazy val serverConfig: ServerConfig = serverConf
      override def router: Router                  = routes(this)
    }.server
  }
}
/**
 * Cake for building a simple Armeria server.
 */
trait ArmeriaServerComponents extends ServerComponents {
  lazy val server: ArmeriaServer = {
    // Start the application first
    Play.start(application)
    new ArmeriaServer(serverConfig, ApplicationProvider(application), serverStopHook, application.actorSystem)(
      application.materializer
    )
  }

  def application: Application
}

/**
 * A convenient helper trait for constructing an ArmeriaServer, for example:
 *
 * {{{
 *   val components = new DefaultArmeriaServerComponents {
 *     override lazy val router = {
 *       case GET(p"/") => Action(parse.json) { body =>
 *         Ok("Hello")
 *       }
 *     }
 *   }
 *   val server = components.server
 * }}}
 */
trait DefaultArmeriaServerComponents extends ArmeriaServerComponents with BuiltInComponents with NoHttpFiltersComponents
