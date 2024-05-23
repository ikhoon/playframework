/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.core.server.armeria

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.FutureOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.websocket.WebSocketProtocolHandler
import com.linecorp.armeria.server.websocket.WebSocketService
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory
import io.netty.handler.codec.http.HttpResponseStatus
import org.apache.pekko.stream.Materializer
import play.api.http.DefaultHttpErrorHandler
import play.api.http.DevHttpErrorHandler
import play.api.http.HeaderNames
import play.api.http.HttpErrorHandler
import play.api.http.Status
import play.api.libs.streams.Accumulator
import play.api.mvc.EssentialAction
import play.api.mvc.RequestHeader
import play.api.mvc.Results
import play.api.mvc.WebSocket
import play.api.Application
import play.api.Logger
import play.api.Mode
import play.core.server.common.{ReloadCache, ServerDebugInfo, ServerResultUtils, WebSocketFlowHandler}
import play.core.server.ArmeriaServer
import play.core.server.Server

private[server] final class PlayHttpService(
    server: ArmeriaServer,
    wsBufferLimit: Int,
    wsKeepAliveMode: String,
    wsKeepAliveMaxIdle: Duration,
) extends HttpService {

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

  private val webSocketProtocolHandler: WebSocketProtocolHandler =
    WebSocketService
      // Armeria WebSocket handler is not used, only the codec is used.
      .builder((_, in) => in)
      // For now, like Netty, select an arbitrary subprotocol from the list of subprotocols proposed by the client
      // Eventually it would be better to allow the handler to specify the protocol it selected
      // See also https://github.com/playframework/playframework/issues/7895
      .subprotocols("*")
      .build()
      .protocolHandler()

  // TODO(ikhoon): Add an option for ExchangeType
  override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse = {
    logger.trace("Http request received by Armeria: " + ctx)

    val tryApp: Try[Application]       = server.applicationProvider.get
    val cacheValues                    = reloadCache.cachedFrom(tryApp)
    val request                        = cacheValues.modelConversion.convertRequest(ctx)
    val debugHeader                    = ServerDebugInfo.attachToRequestHeader(request, cacheValues.serverDebugInfo)
    val (taggedRequestHeader, handler) = Server.getHandlerFor(debugHeader, tryApp, fallbackErrorHandler)

    implicit val mat: Materializer = tryApp match {
      case Success(app) => app.materializer
      case Failure(_)   => server.materializer
    }

    import play.core.Execution.Implicits.trampoline
    val future: Future[HttpResponse] = handler match {
      case action: EssentialAction =>
        handleAction(action, taggedRequestHeader, req, tryApp)
      case ws: WebSocket if req.headers().get(HttpHeaderNames.UPGRADE, "").equalsIgnoreCase("websocket") =>
        logger.trace("Serving this request with: " + ws)
        ws(taggedRequestHeader).flatMap {
          case Left(result) =>
            modelConversion(tryApp).convertResult(result, taggedRequestHeader, errorHandler(tryApp))
          case Right(flow) =>
            val processor = WebSocketFlowHandler
              .webSocketProtocol(wsBufferLimit, wsKeepAliveMode, wsKeepAliveMaxIdle)
              .join(flow)
              .toProcessor
              .run()
            Future.successful(WebSocketHandler.handleWebSocket(webSocketProtocolHandler, ctx, req, processor))
        }
      // handle bad websocket request
      case ws: WebSocket =>
        logger.trace(s"Bad websocket request: $request")
        val action = EssentialAction(_ =>
          Accumulator.done(
            Results
              .Status(Status.UPGRADE_REQUIRED)("Upgrade to WebSocket required")
              .withHeaders(
                HeaderNames.UPGRADE    -> "websocket",
                HeaderNames.CONNECTION -> HeaderNames.UPGRADE
              )
          )
        )
        handleAction(action, taggedRequestHeader, req, tryApp)
      case h =>
        val ex = new IllegalStateException(s"Armeria server doesn't handle Handlers of this type: $h")
        logger.error(ex.getMessage, ex)
        throw ex
    }
    HttpResponse.of(future.asJava)
  }

  private def handleAction(
      action: EssentialAction,
      requestHeader: RequestHeader,
      request: HttpRequest,
      tryApp: Try[Application]
  )(implicit mat: Materializer, ec: ExecutionContext) : Future[HttpResponse] = {

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
        val serverResultUtils = resultUtils(tryApp)
        val cleanedResult     = serverResultUtils.prepareCookies(requestHeader, actionResult)
        serverResultUtils.validateResult(requestHeader, cleanedResult, errorHandler(tryApp))
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
