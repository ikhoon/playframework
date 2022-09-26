/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */

package play.core.server.armeria

import scala.jdk.CollectionConverters.IteratorHasAsScala

object ArmeriaCollectionUtil {

  /**
   * Convert a Java `List` into Scala `Seq` without copying elements because Armeria always returns an
   * immutable `List` for public API.
   */
  def toSeq[A](immutableList: java.util.List[A]): Seq[A] = {
    val size = immutableList.size()
    size match {
      case 0 => Nil
      case 1 => Seq(immutableList.get(0))
      case _ =>
        new Seq[A] {
          override def apply(i: Int): A = immutableList.get(i)

          override def length: Int = size

          override def iterator: Iterator[A] = immutableList.iterator().asScala
        }
    }
  }
}
