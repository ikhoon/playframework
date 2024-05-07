/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.core.server.armeria

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.linecorp.armeria.common.stream.StreamMessage
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.HttpHeaderValues
import java.net.{InetAddress, InetSocketAddress, URI}
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import play.api.http.HeaderNames.SET_COOKIE
import play.api.http.HttpChunk.Chunk
import play.api.http.HttpChunk.LastChunk
import play.api.http.HttpEntity
import play.api.http.HttpErrorHandler
import play.api.libs.typedmap.TypedMap
import play.api.mvc.request.RemoteConnection
import play.api.mvc.request.RequestAttrKey
import play.api.mvc.request.RequestTarget
import play.api.mvc.Headers
import play.api.mvc.RequestHeader
import play.api.mvc.RequestHeaderImpl
import play.api.mvc.Result
import play.core.server.armeria.ArmeriaCollectionUtil.toSeq
import play.core.server.armeria.ArmeriaModelConversion.logger
import play.core.server.common.ForwardedHeaderHandler
import play.core.server.common.ServerResultUtils
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

private[armeria] final class ArmeriaModelConversion(
    resultUtils: ServerResultUtils,
    forwardedHeaderHandler: ForwardedHeaderHandler,
) {

  /**
   * Convert an Armeria `HttpRequest` to a Play `RequestHeader`.
   */
  def convertRequest(ctx: ServiceRequestContext): RequestHeader = {
    val headers    = new ArmeriaHeadersWrapper(ctx.request().headers())
    val connection = createRemoteConnection(ctx, headers)
    val target     = createRequestTarget(ctx)
    val version    = createHttpVersion(ctx)
    new RequestHeaderImpl(
      connection,
      ctx.method().name(),
      target,
      version,
      headers,
      // Send an attribute so our tests can tell which kind of server we're using.
      // We only do this for the "non-default" engine so that benchmarking isn't affected by this.
      TypedMap(RequestAttrKey.Server -> "armeria")
    )
  }

  /**
   * Convert an Armeria request body into a `Source`.
   */
  def convertRequestBody(request: HttpRequest): Option[Source[ByteString, Any]] = {
    val contentLength = request.headers().contentLength()
    if (contentLength == 0) {
      None
    } else {
      val body: StreamMessage[ByteString] =
        request
          .filter(obj => obj.isInstanceOf[HttpData])
          .map(data => ByteString.fromArrayUnsafe(data.asInstanceOf[HttpData].array()))
      Some(Source.fromPublisher(body))
    }
  }

  /** Create a new Armeria response from the result */
  def convertResult(result: Result, requestHeader: RequestHeader, errorHandler: HttpErrorHandler)(
      implicit mat: Materializer
  ): Future[HttpResponse] = {
    resultUtils.resultConversionWithErrorHandling(requestHeader, result, errorHandler) { result =>
      val status = result.header.reasonPhrase match {
        case Some(phrase) => new HttpStatus(result.header.status, phrase)
        case None         => HttpStatus.valueOf(result.header.status)
      }

      val headers        = resultUtils.splitSetCookieHeaders(result.header.headers)
      val headersBuilder = ResponseHeaders.builder(status)

      headers.foreach {
        case (SET_COOKIE, value) =>
          resultUtils.splitSetCookieHeaderValue(value).foreach { cookiePart =>
            headersBuilder.add(HttpHeaderNames.SET_COOKIE, cookiePart)
          }
        case (name, value) =>
          headersBuilder.add(name, value)
      }

      if (resultUtils.mayHaveEntity(result.header.status)) {
        result.body.contentLength.foreach { contentLength =>
          val manualContentLength = headersBuilder.contentLength()
          if (manualContentLength == -1) {
            headersBuilder.contentLength(contentLength)
          } else if (manualContentLength != contentLength) {
            logger.warn(
              s"Content-Length header was set manually in the header ($manualContentLength) but is not the " +
                s"same as actual content length ($contentLength). Ignoring manual header."
            )
            headersBuilder.contentLength(contentLength)
          }
        }
      }

      result.body.contentType.foreach { contentType =>
        val contentType0 = headersBuilder.contentType()
        if (contentType0 == null) {
          headersBuilder.contentType(MediaType.parse(contentType))
        } else {
          logger.warn(
            s"Content-Type set both in header ($contentType0}) and attached to " +
              s"entity ($contentType), ignoring content type from entity. To remove this warning, use Result" +
              s".as(...) to set the content type, rather than setting the header manually."
          )
        }
      }

      // TODO(ikhoon): Add Date and Server headers.

      val response: HttpResponse = result.body match {
        case HttpEntity.Strict(data, _) =>
          if (data.isEmpty) {
            HttpResponse.of(headersBuilder.build())
          } else {
            HttpResponse.of(headersBuilder.build(), toHttpData(data))
          }

        case HttpEntity.Streamed(stream, _, _) =>
          val publisher = stream.map(toHttpData).runWith(Sink.asPublisher(false))
          if (!headersBuilder.contains(HttpHeaderNames.CONTENT_LENGTH)) {
            headersBuilder.set(HttpHeaderNames.CONTENT_LENGTH, "-1");
          }
          HttpResponse.of(headersBuilder.build(), publisher)

        case HttpEntity.Chunked(chunks, _) =>
          val publisher = chunks
            .map {
              case Chunk(data) => toHttpData(data)
              case LastChunk(trailers) =>
                if (trailers.headers.isEmpty) {
                  HttpHeaders.of()
                } else {
                  val builder = HttpHeaders.builder()
                  trailers.headers.foreach { case (name, value) => builder.add(name, value) }
                  builder.build()
                }
            }
            .runWith(Sink.asPublisher(false))
//          if (!headersBuilder.contains(HttpHeaderNames.CONTENT_LENGTH)) {
//            headersBuilder.set(HttpHeaderNames.CONTENT_LENGTH, "-1");
//          }
          headersBuilder.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED.toString())
          HttpResponse.of(headersBuilder.build(), publisher)
      }
      Future.successful(response)
    } {
      // TODO(ikhoon): Add Date and Server headers.
      HttpResponse.of(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR))
    }
  }

  /**
   * Convert a ByteString into a Armeria HttpData.
   */
  private def toHttpData(data: ByteString): HttpData =
    HttpData.wrap(Unpooled.wrappedBuffer(data.asByteBuffer))

  /**
   * Return the HTTP protocol version of the request.
   */
  private def createHttpVersion(ctx: ServiceRequestContext): String = {
    if (ctx.sessionProtocol().isMultiplex) {
      "HTTP/2.0"
    } else {
      if (ctx.request().headers().get(HttpHeaderNames.HOST) != null) {
        "HTTP/1.1"
      } else {
        "HTTP/1.0"
      }
    }
  }

  /** Capture a request's connection info from the request context and headers. */
  private def createRemoteConnection(ctx: ServiceRequestContext, headers: Headers): RemoteConnection = {
    val connection = new RemoteConnection {
      override def remoteAddress: InetAddress = ctx.remoteAddress[InetSocketAddress]().getAddress

      override def secure: Boolean = ctx.sessionProtocol().isTls

      override def clientCertificateChain: Option[Seq[X509Certificate]] = {
        try {
          Option(ctx.sslSession()).map(_.getPeerCertificates.toSeq.collect {
            case x509: X509Certificate =>
              x509
          })
        } catch {
          case _: SSLPeerUnverifiedException => None
        }
      }
    }

    forwardedHeaderHandler.forwardedConnection(connection, headers)
  }

  /** Create request target information from the Armeria request . */
  private def createRequestTarget(ctx: ServiceRequestContext): RequestTarget = {
    new RequestTarget {
      override lazy val uri: URI = new URI(ctx.path())

      override lazy val uriString: String = {
        if (ctx.query() == null) ctx.path() else s"${ctx.path()}?${ctx.query()}"
      }

      override def path: String = ctx.path()

      override lazy val queryMap: Map[String, Seq[String]] = {
        val queryParams = ctx.queryParams()
        queryParams
          .names()
          .asScala
          .map(name => name -> toSeq(queryParams.getAll(name)))
          .toMap
      }

      override def getQueryParameter(key: String): Option[String] = Option(ctx.queryParam(key))
    }
  }
}

private object ArmeriaModelConversion {

  private val logger: Logger = LoggerFactory.getLogger(classOf[ArmeriaModelConversion])
}
