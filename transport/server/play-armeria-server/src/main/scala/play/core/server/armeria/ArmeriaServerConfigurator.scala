/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */

package play.core.server.armeria

import com.linecorp.armeria.server.ServerBuilder

/**
 * Configure the Armeria server. Can be used to customize options or register arbitrary services such
 * gRPC service or Thrift service.
 */
trait ArmeriaServerConfigurator {

  /**
   * Configures the Armeria server using the specified `ServerBuilder`.
   */
  def configure(serverBuilder: ServerBuilder): Unit
}
