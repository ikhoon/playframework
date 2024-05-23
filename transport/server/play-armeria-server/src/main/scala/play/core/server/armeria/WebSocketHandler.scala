/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.core.server.armeria

import com.linecorp.armeria.common.stream.StreamMessage
import com.linecorp.armeria.common.websocket.{WebSocket, WebSocketCloseStatus, WebSocketFrame, WebSocketFrameType}
import com.linecorp.armeria.common.{HttpRequest, HttpResponse}
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.websocket.WebSocketProtocolHandler
import io.netty.buffer.{ByteBuf, Unpooled}
import org.apache.pekko.util.ByteString
import org.reactivestreams.Processor
import play.api.http.websocket.*
import play.core.server.common.WebSocketFlowHandler.{MessageType, RawMessage}

private[armeria] object WebSocketHandler {

  private val EMPTY_BYTES: Array[Byte] = Array()

  def handleWebSocket(
      protocolHandler: WebSocketProtocolHandler,
      ctx: ServiceRequestContext,
      req: HttpRequest,
      processor: Processor[RawMessage, Message]
  ): HttpResponse = {
    val in = protocolHandler.decode(ctx, req)
    in.map(frameToMessage).subscribe(processor, ctx.eventLoop())
    val out = WebSocket.of(StreamMessage.of(processor).map(messageToFrame))
    protocolHandler.encode(ctx, out)
  }

  /**
   * Converts Armeria frames to Play RawMessages.
   */
  private def frameToMessage(frame: WebSocketFrame): RawMessage = {
    val bytes = ByteString.fromArray(frame.array())

    val messageType = frame.`type`() match {
      case WebSocketFrameType.CONTINUATION => MessageType.Continuation
      case WebSocketFrameType.TEXT         => MessageType.Text
      case WebSocketFrameType.BINARY       => MessageType.Binary
      case WebSocketFrameType.CLOSE        => MessageType.Close
      case WebSocketFrameType.PING         => MessageType.Ping
      case WebSocketFrameType.PONG         => MessageType.Pong
    }
    RawMessage(messageType, bytes, frame.isFinalFragment)
  }

  /**
   * Converts Play messages to Armeria frames.
   */
  private def messageToFrame(message: Message): WebSocketFrame = {
    def byteStringToByteBuf(bytes: ByteString): ByteBuf = {
      if (bytes.isEmpty) {
        Unpooled.EMPTY_BUFFER
      } else {
        Unpooled.wrappedBuffer(bytes.asByteBuffer)
      }
    }

    message match {
      case TextMessage(data)   => WebSocketFrame.ofText(data)
      case BinaryMessage(data) => WebSocketFrame.ofPooledBinary(byteStringToByteBuf(data))
      case PingMessage(data)   => WebSocketFrame.ofPooledPing(byteStringToByteBuf(data))
      case PongMessage(data)   => WebSocketFrame.ofPooledPong(byteStringToByteBuf(data))
      case CloseMessage(Some(statusCode), reason) =>
        WebSocketFrame.ofClose(WebSocketCloseStatus.valueOf(statusCode), reason)
      case CloseMessage(None, _) => WebSocketFrame.ofClose(EMPTY_BYTES)
    }
  }
}
