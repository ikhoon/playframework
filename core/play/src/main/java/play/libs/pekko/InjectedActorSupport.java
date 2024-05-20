/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.libs.pekko;

import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.pekko.actor.Actor;
import org.apache.pekko.actor.ActorContext;
import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.Props;

/** Support for creating injected child actors. */
public interface InjectedActorSupport {

  /**
   * Create an injected child actor.
   *
   * @param create A function to create the actor.
   * @param name The name of the actor.
   * @param props A function to provide props for the actor. The props passed in will just describe
   *     how to create the actor, this function can be used to provide additional configuration such
   *     as router and dispatcher configuration.
   * @return An ActorRef for the created actor.
   */
  default ActorRef injectedChild(
      Supplier<Actor> create, String name, Function<Props, Props> props) {
    return context().actorOf(props.apply(Props.create(Actor.class, create::get)), name);
  }

  /**
   * Create an injected child actor.
   *
   * @param create A function to create the actor.
   * @param name The name of the actor.
   * @return An ActorRef for the created actor.
   */
  default ActorRef injectedChild(Supplier<Actor> create, String name) {
    return injectedChild(create, name, Function.identity());
  }

  /**
   * Context method expected to be implemented by {@link org.apache.pekko.actor.AbstractActor}.
   *
   * @return the ActorContext.
   */
  ActorContext context();
}
