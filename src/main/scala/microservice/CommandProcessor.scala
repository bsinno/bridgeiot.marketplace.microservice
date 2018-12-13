/**
 * Copyright (c) 2016-2017 Atos IT Solutions and Services GmbH
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package microservice

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.FanOutShape.{Init, Name}
import akka.stream._
import akka.stream.scaladsl.GraphDSL.Implicits._
import akka.stream.scaladsl.{GraphDSL, Merge, RunnableGraph}
import akka.stream.stage._

import io.circe.{Decoder, Encoder}
import io.funcqrs.akka.EventsSourceProvider
import io.funcqrs.akka.backend.AkkaBackend
import io.funcqrs.backend.{Query, QuerySelectAll}
import io.funcqrs.behavior.{Behavior, Types}
import io.funcqrs.config.Api.{aggregate, projection}
import io.funcqrs.{AggregateId, Projection, Tags}
import microservice.persistence.CassandraProjectionSourceProvider
import scala.concurrent.ExecutionContext.Implicits.global
import scala.languageFeature.higherKinds
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

case class CommandProcessorShape[Cmd <: Command, Ev <: Event](init: Init[Committable[Cmd]] = Name[Committable[Cmd]]("CommandProcessor"))
  extends FanOutShape[Committable[Cmd]](init) {
  protected override def construct(init: Init[Committable[Cmd]]) = CommandProcessorShape(init)

  val events = newOutlet[Ev]("event")
  val errors = newOutlet[Error]("error")
}

case class CommandProcessor[A: ClassTag, I <: AggregateId, Cmd <: Command, Ev <: Event]
(backend: AkkaBackend, name: String)(implicit fromString: String => I, types: Types[A] {type Id = I; type Command = Cmd; type Event = Ev})
  extends GraphStage[CommandProcessorShape[Cmd, Ev]] {

  val shape = CommandProcessorShape[Cmd, Ev]()

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with StageLogging {
      var eventDemand = false
      var errorDemand = false

      setHandler(shape.in, new InHandler {
        override def onPush() = {
          val cmdMessage = grab(shape.in)
          val rec = cmdMessage.record
          val cmd = rec.value
          val id = cmd.id.value

          log.debug(s"$name received $cmd for id: $id")

          val aggregate = backend.aggregateRef[A].forId(id)
          log.debug(s"  aggregate: $aggregate")

          (aggregate ? cmd)
            .onComplete {
              getAsyncCallback[Try[Seq[Ev]]] {
                case Success(events) =>
                  try {
                    eventDemand = false
                    log.info(s"$name created $events for $cmd in ${rec.topic} at offset ${rec.offset}")
                    cmdMessage.committableOffset.commitScaladsl()
                      .map {
                        getAsyncCallback[Done] { _ =>
                          log.info(s"$name committed $cmd in ${rec.topic} at offset ${rec.offset}")
                          emitMultiple(shape.events, events.toList)
                        }.invoke
                      }
                  } catch {
                    case e: Throwable =>
                      log.error(s"$name throws error: ${e.getMessage}, committed $cmd in ${rec.topic} at offset ${rec.offset}")
                  }
                case Failure(err) =>
                  errorDemand = false
                  val error = err match {
                    case e: Error => e
                    case e: Throwable => new Error(id, e.getClass.getSimpleName, e.getMessage, meta = cmd.meta)
                  }
                  log.info(s"$name created error $error for $cmd in ${rec.topic} at offset ${rec.offset}")
                  cmdMessage.committableOffset.commitScaladsl()
                    .map {
                      getAsyncCallback[Done] { _ =>
                        log.info(s"$name committed $cmd in ${rec.topic} at offset ${rec.offset}")
                        emit(shape.errors, error)
                      }.invoke
                    }
              }.invoke
            }
        }
      })

      setHandler(shape.events, new OutHandler {
        override def onPull(): Unit = {
          eventDemand = true
          if (eventDemand && errorDemand)
            pull(shape.in)
        }
      })

      setHandler(shape.errors, new OutHandler {
        override def onPull(): Unit = {
          errorDemand = true
          if (eventDemand && errorDemand)
            pull(shape.in)
        }
      })
    }
}

abstract class AkkaServiceBackend[A: ClassTag, I <: AggregateId, Cmd <: Command, Ev <: Event]
  (val serviceName: String, behavior: I => Behavior[A, Cmd, Ev], viewProjections: List[Projection] = Nil)
  (implicit system: ActorSystem) extends AkkaBackend {
  val actorSystem = system
  val tag = Tags.aggregateTag(serviceName)

  val aggr = aggregate(behavior)
  configure {
    aggr
  }

  viewProjections foreach { prj =>
    configure {
      projection(
        query = QuerySelectAll,
        projection = prj,
        name = serviceName + "ViewProjection"
      )
    }
  }

  def sourceProvider(query: Query): EventsSourceProvider = {
    query match {
      case _ => CassandraProjectionSourceProvider(tag)
    }
  }

  def validator: Option[PartialFunction[Cmd, Either[Error, Cmd]]] = None

  def eventAdapter: Option[RunnableGraph[NotUsed]] = None
}

object CommandProcessorStage {
  def apply[A: ClassTag, I <: AggregateId, Cmd <: Command, Ev <: Event]
    (backend: AkkaServiceBackend[A, I, Cmd, Ev])
    (implicit fromString: String => I, types: Types[A] {type Id = I; type Command = Cmd; type Event = Ev},
     system: ActorSystem, mat: ActorMaterializer,
     d1: Decoder[Cmd], d2: Decoder[Ev], e1: Encoder[Cmd], e2: Encoder[Ev]) = {
    RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
      val name = backend.serviceName + "Processor" + envSuffix
      val cmdTopicSource = b.add(TopicSource(cmdTopic[Cmd](backend.serviceName), name))
      val eventTopicSink = b.add(TopicSink(eventTopic[Ev](backend.serviceName), name))
      val errorTopicSink = b.add(TopicSink(errorTopic, name))

      val cmdProcessor = b.add(CommandProcessor(backend, name))

      backend.validator map { validator =>
        val cmdValidator = b.add(CommandValidator(validator, name))
        val errorMerge = b.add(Merge[Error](2))

        cmdTopicSource ~> cmdValidator.in
        cmdValidator.validatedCmds ~> cmdProcessor.in
        cmdValidator.errors ~> errorMerge.in(0)
        cmdProcessor.errors ~> errorMerge.in(1)
        errorMerge.out ~> errorTopicSink
        cmdProcessor.events ~> eventTopicSink
        ClosedShape
      } getOrElse {
        cmdTopicSource ~> cmdProcessor.in
        cmdProcessor.events ~> eventTopicSink
        cmdProcessor.errors ~> errorTopicSink
        ClosedShape
      }
    }).run()
    backend.eventAdapter map (_.run())
  }
}
