/**
 * Copyright (c) 2016-2017 Atos IT Solutions and Services GmbH
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

import java.nio.charset.StandardCharsets
import scala.collection.immutable
import scala.languageFeature.implicitConversions
import scala.util.Properties
import akka.http.scaladsl.model.DateTime
import akka.kafka.ConsumerMessage.CommittableMessage

import io.circe.generic.extras.Configuration
import io.circe.generic.extras.auto._
import io.circe.{Decoder, Encoder, HCursor, Json}
import io.funcqrs.AggregateId
import io.funcqrs.behavior.Actions
import io.funcqrs.behavior.handlers.ManyEventsIdentity
import sangria.execution.UserFacingError
import microservice.entity._

package object microservice {

  implicit val circeConfiguration: Configuration = Configuration.default.withDefaults

  def isAnonymous(requesterId: Option[String]) =
    requesterId.forall(_.isEmpty)
  def hasNoOrganization(requesterId: Option[String], requesterOrgId: Option[String]) =
    isAnonymous(requesterId) || requesterOrgId.forall(_.isEmpty)
  def hasWrongOrganization(requesterId: Option[String], requesterOrgId: Option[Id], id: Id) =
    hasNoOrganization(requesterId, requesterOrgId) || !requesterOrgId.contains(entity.organizationId(id))

  case class Meta(requesterId: Option[String] = None, requesterOrgId: Option[Id] = None, requestId: String = generateId(),
                  time: Long = DateTime.now.clicks, requestTime: Long = DateTime.now.clicks, var delay: Int = 0, var finished: Boolean = true) {
    override def toString =
      s"Meta(${requestId.substring(0, 8)}" + (if (delay > 0) s",delay=$delay" else "") + (if (!finished) ",unfinished" else "") + ")"
    def delayed(delta: Int = 1) = copy(delay = delay + delta)
    def isAnonymous = microservice.isAnonymous(requesterId)
    def hasNoOrganization = microservice.hasNoOrganization(requesterId, requesterOrgId)
    def hasWrongOrganization(id: Id) = microservice.hasWrongOrganization(requesterId, requesterOrgId, id)
    def authInfo = if (isAnonymous) "anonymous" else s"${requesterId.get}, $requesterOrgId"
  }

  def markLast[Ev <: Event](events: List[Ev]) = {
    val last = events.last

    events map { event =>
      if (event == last)
        event.meta.finished = true
      else
        event.meta.finished = false
      event
    }
  }

  trait Aggregate[A, C, E] {
    def acceptCommands: Actions[A, C, E]
  }

  abstract class Event {
    def id: AggregateId
    def meta: Meta
  }

  trait Unchanged

  trait Command {
    def id: AggregateId
    def meta: Meta
    def isAnonymous = meta.isAnonymous
    def hasOrganization = !hasNoOrganization
    def hasNoOrganization = meta.hasNoOrganization
    def hasWrongOrganization = meta.hasWrongOrganization(id)
  }

  trait CreationCommand extends Command {
    def name: String
  }

  trait HierarchicalCreationCommand extends CreationCommand {
    def parentId: Id
    def localId: Option[Id]
  }

  trait OrganizationChildId extends AggregateId {
    def organizationId = value.substring(0, value.indexOf(Sep))
  }

  class Error(val id: Id, val name: String, val error: String = "", val meta: Meta)
    extends Throwable(s"$name: ${if (error.isEmpty) id else error}") with UserFacingError {
    override def toString = s"Error($id, $name, $error, $meta)"
  }

  case class NotAuthorized(cmd: Command, msg: String = "") extends
    Error(cmd.id, "NotAuthorized", s"$cmd by ${cmd.meta.authInfo}. $msg", cmd.meta)

  object markLast {
    case class ManyEvents[C, E](handler: PartialFunction[C, immutable.Seq[E]]) extends ManyEventsIdentity(handler)
  }

  type Committable[E] = CommittableMessage[String, E]

  case class TopicDesc[Msg](topic: String, decoder: Decoder[Msg], encoder: Encoder[Msg])

  implicit def encodeAggregateId[Id <: AggregateId]: Encoder[Id] = new Encoder[Id] {
    final def apply(id: Id): Json = Json.fromString(id.value)
  }

  implicit def decodeAggregateId[Id <: AggregateId](implicit fromString: String => Id): Decoder[Id] =
    Decoder[String].emap(value => Right(fromString(value)))

  implicit val encodeError: Encoder[Error] = new Encoder[Error] {
    final def apply(err: Error): Json = Json.obj(
      ("id", Json.fromString(err.id)),
      ("meta", implicitly[Encoder[Meta]].apply(err.meta)),
      ("error", Json.fromString(err.error)),
      ("name", Json.fromString(err.name))
    )
  }

  implicit val decodeError: Decoder[Error] = new Decoder[Error] {
    final def apply(c: HCursor): Decoder.Result[Error] =
      for {
        id <- c.downField("id").as[String]
        meta <- c.downField("meta").as[Meta]
        error <- c.downField("error").as[String]
        name <- c.downField("name").as[String]
      } yield {
        new Error(id, name, error, meta)
      }
  }

  implicit def aggregateIdToString(id: AggregateId): String = id.value

  def cmdTopic[Cmd](name: String)(implicit decoder: Decoder[Cmd], encoder: Encoder[Cmd]) =
    TopicDesc[Cmd](commandTopicName(name), decoder, encoder)
  def eventTopic[Ev](name: String)(implicit decoder: Decoder[Ev], encoder: Encoder[Ev]) =
    TopicDesc[Ev](eventTopicName(name), decoder, encoder)
  def errorTopic(implicit decoder: Decoder[Error], encoder: Encoder[Error]) =
    TopicDesc[Error]("Errors" + envSuffix, decoder, encoder)

  val envSuffix = Properties.envOrNone("MARKETENV") map ("-" + _) getOrElse ""

  def commandTopicName(serviceName: String) = serviceName + "Commands" + envSuffix
  def eventTopicName(serviceName: String) = serviceName + "Events" + envSuffix


  implicit def cmd2List[Cmd <: Command](cmd: Cmd): List[Cmd] = List(cmd)

  val UTF_8 = StandardCharsets.UTF_8.name()

}
