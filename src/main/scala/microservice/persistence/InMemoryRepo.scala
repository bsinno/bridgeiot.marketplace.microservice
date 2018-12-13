/**
 * Copyright (c) 2016-2017 Atos IT Solutions and Services GmbH
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package microservice.persistence

import scala.util.Try

import io.funcqrs.AggregateId
import microservice.entity.Entity

trait ReadableRepo[E <: Entity, Id <: AggregateId] {
  def find(id: Id): Try[E]
}

trait InMemoryRepo[E <: Entity, Id <: AggregateId] extends ReadableRepo[E, Id] {

  var store: Map[AggregateId, E] = Map()

  def find(id: Id): Try[E] = Try(store(id))

  def save(model: E): Unit = {
    store = store + ($id(model) -> model)
  }

  def deleteById(id: Id): Unit =
    store = store.filterKeys(_ != id)

  def updateById(id: Id)(updateFunc: (E) => E): Try[E] =
    find(id).map { model =>
      val updated = updateFunc(model)
      save(updated)
      updated
    }

  def fetchAll: Seq[E] =
    store.values.toSeq

  /** Extract id from Model */
  protected def $id(model: E): AggregateId = model.id

}
