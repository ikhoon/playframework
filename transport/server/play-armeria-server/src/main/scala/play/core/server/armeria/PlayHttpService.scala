/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */

package play.core.server.armeria

import akka.stream.Materializer
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import play.api.Application
import play.api.Logger
import play.api.Mode
import play.api.http.DefaultHttpErrorHandler
import play.api.http.DevHttpErrorHandler
import play.api.http.HttpErrorHandler
import play.api.mvc.EssentialAction
import play.api.mvc.RequestHeader
import play.api.mvc.WebSocket
import play.core.server.ArmeriaServer
import play.core.server.Server
import play.core.server.common.ReloadCache
import play.core.server.common.ServerDebugInfo
import play.core.server.common.ServerResultUtils
import scala.concurrent.Future
import scala.jdk.FutureConverters.FutureOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try

private[server] final class PlayHttpService(server: ArmeriaServer) extends HttpService {

  import PlayHttpService._

  private lazy val fallbackErrorHandler = server.mode match {
    case Mode.Prod => DefaultHttpErrorHandler
    case _         => DevHttpErrorHandler
  }

  /**
   * Values that are cached based on the current application.
   */
  private case class ReloadCacheValues(
      resultUtils: ServerResultUtils,
      modelConversion: ArmeriaModelConversion,
      serverDebugInfo: Option[ServerDebugInfo]
  )

  /**
   * A helper to cache values that are derived from the current application.
   */
  private val reloadCache = new ReloadCache[ReloadCacheValues] {
    protected override def reloadValue(tryApp: Try[Application]): ReloadCacheValues = {
      val oldClassLoader = Thread.currentThread().getContextClassLoader
      try {
        Thread.currentThread().setContextClassLoader(PlayHttpService.getClass.getClassLoader)
        val serverResultUtils      = reloadServerResultUtils(tryApp)
        val forwardedHeaderHandler = reloadForwardedHeaderHandler(tryApp)
        val modelConversion        = new ArmeriaModelConversion(serverResultUtils, forwardedHeaderHandler)
        ReloadCacheValues(
          resultUtils = serverResultUtils,
          modelConversion = modelConversion,
          serverDebugInfo = reloadDebugInfo(tryApp, ArmeriaServer.provider)
        )
      } finally {
        Thread.currentThread().setContextClassLoader(oldClassLoader)
      }
    }
  }

  // TODO(ikhoon): Add an option for ExchangeType
  override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse = {
    logger.trace("Http request received by Armeria: " + ctx)

    val tryApp: Try[Application] = server.applicationProvider.get
    val cacheValues              = reloadCache.cachedFrom(tryApp)
    val request                  = cacheValues.modelConversion.convertRequest(ctx)
    val debugHeader              = ServerDebugInfo.attachToRequestHeader(request, cacheValues.serverDebugInfo)
    val (requestHeader, handler) = Server.getHandlerFor(debugHeader, tryApp, fallbackErrorHandler)

    val future: Future[HttpResponse] = handler match {
      case action: EssentialAction =>
        handleAction(action, requestHeader, req, tryApp)
      case _: WebSocket =>
        // TODO(ikhoon): Support WebSocket when https://github.com/line/armeria/pull/3904 is merged.
        val ex = new UnsupportedOperationException("Armeria server can't handle WebSocket")
        logger.error(ex.getMessage, ex)
        throw ex
      case h =>
        val ex = new IllegalStateException(s"Armeria server doesn't handle Handlers of this type: $h")
        logger.error(ex.getMessage, ex)
        throw ex
    }
    HttpResponse.from(future.asJava)
  }

  private def handleAction(
      action: EssentialAction,
      requestHeader: RequestHeader,
      request: HttpRequest,
      tryApp: Try[Application]
  ): Future[HttpResponse] = {
    implicit val mat: Materializer = tryApp match {
      case Success(app) => app.materializer
      case Failure(_)   => server.materializer
    }
    import play.core.Execution.Implicits.trampoline

    // Execute the action on the Play default execution context
    val actionFuture = Future(action(requestHeader))(mat.executionContext)
    for {
      actionResult <- actionFuture
        .flatMap { acc =>
          val body = modelConversion(tryApp).convertRequestBody(request)
          body match {
            case None         => acc.run()
            case Some(source) => acc.run(source)
          }
        }
        .recoverWith {
          case error =>
            logger.error("Cannot invoke the action", error)
            errorHandler(tryApp).onServerError(requestHeader, error)
        }
      // Clean and validate the action's result
      validatedResult <- {
        val cleanedResult = resultUtils(tryApp).prepareCookies(requestHeader, actionResult)
        resultUtils(tryApp).validateResult(requestHeader, cleanedResult, errorHandler(tryApp))
      }
      // Convert the result to a Netty HttpResponse
      convertedResult <- modelConversion(tryApp)
        .convertResult(validatedResult, requestHeader, errorHandler(tryApp))
    } yield convertedResult
  }

  private def errorHandler(tryApp: Try[Application]): HttpErrorHandler =
    tryApp match {
      case Success(app) => app.errorHandler
      case Failure(_)   => fallbackErrorHandler
    }

  private def modelConversion(tryApp: Try[Application]): ArmeriaModelConversion =
    reloadCache.cachedFrom(tryApp).modelConversion

  private def resultUtils(tryApp: Try[Application]): ServerResultUtils =
    reloadCache.cachedFrom(tryApp).resultUtils
}

private object PlayHttpService {
  private val logger: Logger = Logger(classOf[PlayHttpService])

}
