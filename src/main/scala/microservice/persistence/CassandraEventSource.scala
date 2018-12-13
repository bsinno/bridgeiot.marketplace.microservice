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

import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{Offset, PersistenceQuery}

import io.funcqrs.Tag

object CassandraEventSource {
  def apply(tag: Tag, offset : Offset = Offset.noOffset)(implicit system: ActorSystem) = {
    val readJournal = PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    readJournal.eventsByTag(tag.value, offset)
  }
}
