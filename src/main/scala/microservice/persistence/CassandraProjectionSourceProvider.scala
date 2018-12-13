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

import akka.NotUsed
import akka.actor.ActorContext
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{EventEnvelope2, PersistenceQuery, Sequence}
import akka.stream.scaladsl.Source

import io.funcqrs.Tag
import io.funcqrs.akka.EventsSourceProvider

case class CassandraProjectionSourceProvider(tag: Tag) extends EventsSourceProvider {

  def source(offset: Long)(implicit context: ActorContext): Source[EventEnvelope2, NotUsed] = {

    val readJournal =
      PersistenceQuery(context.system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

    readJournal.eventsByTag(tag.value, Sequence(offset))
  }

}
