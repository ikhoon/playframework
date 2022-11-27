/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.core.server.armeria

import com.linecorp.armeria.common.RequestHeaders
import play.api.mvc.Headers
import play.core.server.armeria.ArmeriaCollectionUtil.toSeq

/**
 * An implementation of Play `Headers` that wraps the raw Armeria headers to
 * avoid additional copies for some common read operations.
 */
private final class ArmeriaHeadersWrapper(private val armeriaHeaders: RequestHeaders) extends Headers(null) {

  override def headers: Seq[(String, String)] = {
    if (_headers == null) {
      // Lazily initialize the header sequence using the Armeria headers. It's OK
      // if we do this operation concurrently because the operation is idempotent.
      val builder = Seq.newBuilder[(String, String)]
      builder.sizeHint(armeriaHeaders.size())
      armeriaHeaders.forEach(entry => {
        builder += entry.getKey.toString() -> entry.getValue
      })
      _headers = builder.result()
    }
    _headers
  }

  override def hasHeader(headerName: String): Boolean = armeriaHeaders.contains(headerName)

  override def hasBody: Boolean = armeriaHeaders.contentLength() != 0

  override def get(key: String): Option[String] = Option(armeriaHeaders.get(key))

  override def apply(key: String): String = {
    val value = armeriaHeaders.get(key)
    if (value == null) scala.sys.error("Header doesn't exist") else value
  }

  override def getAll(key: String): Seq[String] = toSeq(armeriaHeaders.getAll(key))

  override def equals(other: Any): Boolean =
    other match {
      case that: ArmeriaHeadersWrapper => that.armeriaHeaders == this.armeriaHeaders
      case _                           => false
    }

  override def toString: String = armeriaHeaders.toString()

  override def hashCode: Int = armeriaHeaders.hashCode()
}
