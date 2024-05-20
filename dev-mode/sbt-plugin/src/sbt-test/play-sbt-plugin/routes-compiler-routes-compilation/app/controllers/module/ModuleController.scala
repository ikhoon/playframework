/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package controllers.module

import javax.inject.Inject

import play.api.mvc._

class ModuleController @Inject() (c: ControllerComponents) extends AbstractController(c) {
  def index = Action {
    Ok
  }
}
