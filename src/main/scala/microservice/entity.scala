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

import java.util.UUID

import io.funcqrs.AggregateId

import scala.util.Try

object entity {

  type Id = String

  case class IdInput(id: Id, clientMutationId: Option[String])

  val Sep = '-'

  def createId(name: String = "", parentId: Id = "", localId: Option[Id] = None): Id = {
    val id = normalize((localId getOrElse name).trim)
    (if (parentId.isEmpty) "" else parentId + Sep) + (if (id.isEmpty) generateId() else id)
  }

  def organizationId(id: Id): Id = {
    val idx = id.indexOf(Sep)
    if (idx < 0) id else id.substring(0, idx)
  }

  def normalize(s: Id) = s.replaceAll("[^a-zA-Z0-9_.*+:@&=,!~';]", "_")

  def generateId() = UUID.randomUUID().toString.replaceAll("-", "_")

  trait Entity {
    def id: AggregateId

    def spaces = "  "
    def show(indent: String) = indent + toString()
  }

  case class DeletedId(value: String) extends AggregateId
  case class DeletedEntity(id: DeletedId) extends Entity

  def entitiesMatch[T <: Entity](id: AggregateId)(entity: T) = entity.id.value == id.value

}
