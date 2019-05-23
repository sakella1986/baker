package com.ing.baker.runtime.scaladsl

import akka.actor.{ActorSystem, Address}
import akka.stream.Materializer
import cats.data.NonEmptyList
import com.ing.baker.runtime.akka.events.BakerEvent
import com.ing.baker.runtime.akka.{AkkaBaker, AkkaBakerConfig}
import com.ing.baker.runtime.common.{ScalaBaker, SensoryEventStatus}
import com.typesafe.config.Config

import scala.concurrent.Future

object Baker {

  def akkaLocalDefault(actorSystem: ActorSystem, materializer: Materializer): AkkaBaker =
    new AkkaBaker(AkkaBakerConfig.localDefault(actorSystem, materializer))

  def akkaClusterDefault(seedNodes: NonEmptyList[Address], actorSystem: ActorSystem, materializer: Materializer): AkkaBaker =
    new AkkaBaker(AkkaBakerConfig.clusterDefault(seedNodes, actorSystem, materializer))

  def akka(config: AkkaBakerConfig): AkkaBaker =
    new AkkaBaker(config)

  def akka(config: Config, actorSystem: ActorSystem, materializer: Materializer): AkkaBaker =
    new AkkaBaker(AkkaBakerConfig.from(config, actorSystem, materializer))

}

/**
  * The Baker is the component of the Baker library that runs one or multiples recipes.
  * For each recipe a new instance can be baked, sensory events can be send and state can be inquired upon
  */
trait Baker extends ScalaBaker[Future] {

  override type Result = SensoryEventResult

  override type Moments = SensoryEventMoments

  /**
    * This registers a listener function.
    *
    * @param pf A partial function that receives the events.
    * @return
    */
  def registerEventListenerPF(pf: PartialFunction[BakerEvent, Unit]): Future[Unit]

  def fireSensoryEventReceived(processId: String, event: Any): Future[SensoryEventStatus] =
    fireSensoryEventReceived(processId, event, None)

  def fireSensoryEventCompleted(processId: String, event: Any): Future[Result] =
    fireSensoryEventCompleted(processId, event, None)

  def fireSensoryEvent(processId: String, event: Any): Moments =
    fireSensoryEvent(processId, event, None)

  def fireSensoryEventReceived(processId: String, event: Any, correlationId: String): Future[SensoryEventStatus] =
    fireSensoryEventReceived(processId, event, Some(correlationId))

  def fireSensoryEventCompleted(processId: String, event: Any, correlationId: String): Future[Result] =
    fireSensoryEventCompleted(processId, event, Some(correlationId))

  def fireSensoryEvent(processId: String, event: Any, correlationId: String): Moments =
    fireSensoryEvent(processId, event, Some(correlationId))

}
