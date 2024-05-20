/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.core.server.pekkohttp

import org.apache.pekko.http.scaladsl.model.headers.Host
import org.specs2.mutable.Specification
import play.api.Configuration

class PekkoServerConfigReaderTest extends Specification {
  "PekkoServerConfigReader.getHostHeader" should {
    "parse Host header without port number" in {
      val reader = new PekkoServerConfigReader(Configuration("default-host-header" -> "localhost"))
      val actual = reader.getHostHeader

      actual must beRight(Host("localhost"))
    }

    "parse Host header with port number" in {
      val reader = new PekkoServerConfigReader(Configuration("default-host-header" -> "localhost:4000"))
      val actual = reader.getHostHeader

      actual must beRight(Host("localhost", 4000))
    }

    "fail to parse an invalid host address" in {
      val reader = new PekkoServerConfigReader(Configuration("default-host-header" -> "localhost://"))
      val actual = reader.getHostHeader

      actual must beLeft
    }
  }
}
