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

import scala.collection.mutable
import scala.concurrent.Promise
import scala.util.{Failure, Success, Try}
import akka.actor.ActorSystem
import akka.stream.FanInShape.{Init, Name}
import akka.stream._
import akka.stream.stage._

import microservice.entity.{Entity, Id}

case class PendingRequest(requestId: Id, promise: Promise[Entity])
case class CompletedRequest(requestId: Id, result: Try[Entity])

case class PendingRequestStatus(size: Int)

case class PendingRequestHandlerShape(init: Init[PendingRequestStatus] = Name("PendingRequestHandler")) extends FanInShape[PendingRequestStatus](init) {
  override protected def construct(init: Init[PendingRequestStatus]) = PendingRequestHandlerShape(init)

  val add = newInlet[PendingRequest]("add")
  val complete = newInlet[CompletedRequest]("complete")
  val error = newInlet[Committable[Error]]("error")
}

case class PendingRequestHandler(name: String)(implicit system: ActorSystem, mat: ActorMaterializer) extends GraphStage[PendingRequestHandlerShape] {
  val shape = PendingRequestHandlerShape()

  val promises: mutable.Map[Id, Promise[Entity]] = mutable.Map.empty

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with StageLogging {
      var added = true
      var completed = true
      var errored = true

      def add(pending: PendingRequest) = {
        promises += (pending.requestId -> pending.promise)
        log.debug(s"$name: request ${pending.requestId.substring(0, 8)} added")
      }

      def success(requestId: Id, entity: Entity) =
        promises.remove(requestId).fold(log.debug(s"$name: request ${requestId.substring(0, 8)} ignoring success")) { promise =>
          promise.success(entity)
          log.debug(s"$name: request ${requestId.substring(0, 8)} succeeded")
        }

      def failure(requestId: Id, error: Throwable) = {
        promises.remove(requestId).fold(log.debug(s"$name: request ${requestId.substring(0, 8)} ignoring failure")) { promise =>
          promise.failure(error)
          log.debug(s"$name: request ${requestId.substring(0, 8)} failed with error: $error")
        }
      }

      setHandler(shape.add, new InHandler {
        override def onPush() = {
          added = true
          val pendingRequest = grab(shape.add)
          add(pendingRequest)
          emit(shape.out, PendingRequestStatus(promises.size))
        }
      })
      setHandler(shape.complete, new InHandler {
        override def onPush() = {
          completed = true
          grab(shape.complete) match {
            case CompletedRequest(requestId, Success(entity)) =>
              success(requestId, entity)

            case CompletedRequest(requestId, Failure(error)) =>
              failure(requestId, error)
          }
          emit(shape.out, PendingRequestStatus(promises.size))
        }
      })
      setHandler(shape.error, new InHandler {
        override def onPush() = {
          errored = true
          val committable = grab(shape.error)
          val error = committable.record.value
          failure(error.meta.requestId, error)
          emit(shape.out, PendingRequestStatus(promises.size))
          committable.committableOffset.commitScaladsl()
        }
      })
      setHandler(shape.out, new OutHandler {
        override def onPull() = {
          if (added) {
            added = false
            pull(shape.add)
          }
          if (completed) {
            completed = false
            pull(shape.complete)
          }
          if (errored) {
            errored = false
            pull(shape.error)
          }
        }
      })
    }
}
