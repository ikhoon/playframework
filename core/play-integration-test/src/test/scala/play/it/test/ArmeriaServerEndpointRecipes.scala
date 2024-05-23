/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.it.test

import play.api.Configuration
import play.api.http.HttpProtocol
import play.api.test.{HttpServerEndpointRecipe, HttpsServerEndpointRecipe, ServerEndpointRecipe}
import play.core.server.ArmeriaServer

object ArmeriaServerEndpointRecipes {

  val ArmeriaHttp11Plaintext = new HttpServerEndpointRecipe(
    "Armeria HTTP HTTP/1.1 (plaintext)",
    ArmeriaServer.provider,
    Configuration.empty,
    Set(HttpProtocol.HTTP_1_1, HttpProtocol.HTTP_1_1),
    Some("armeria")
  )

  val ArmeriaHttp11Encrypted = new HttpsServerEndpointRecipe(
    "Armeria HTTP HTTP/1.1 (encrypted)",
    ArmeriaServer.provider,
    Configuration.empty,
    Set(HttpProtocol.HTTP_1_1, HttpProtocol.HTTP_1_1),
    Some("armeria")
  )

  val ArmeriaHttp20Plaintext = new HttpServerEndpointRecipe(
    "Armeria HTTP HTTP/2 (plaintext)",
    ArmeriaServer.provider,
    Configuration.empty,
    Set(HttpProtocol.HTTP_1_1, HttpProtocol.HTTP_1_1, HttpProtocol.HTTP_2_0),
    Some("armeria")
  )

  // TODO(ikhoon): Continue to work on HTTPS when https://github.com/line/armeria/pull/5228 is merged.
  val ArmeriaHttp20Encrypted = new HttpsServerEndpointRecipe(
    "Armeria HTTP HTTP/2 (encrypted)",
    ArmeriaServer.provider,
    Configuration.empty,
    Set(HttpProtocol.HTTP_1_1, HttpProtocol.HTTP_1_1, HttpProtocol.HTTP_2_0),
    Some("armeria")
  )

  val AllRecipes: Seq[ServerEndpointRecipe] = Seq(
    ArmeriaHttp11Plaintext,
    ArmeriaHttp11Encrypted,
    ArmeriaHttp20Plaintext,
    ArmeriaHttp20Encrypted
  )
}
