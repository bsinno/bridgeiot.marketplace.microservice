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

import akka.persistence.journal.{Tagged, WriteEventAdapter}

import io.funcqrs.Tag

class TagWriteEventAdapter[Ev](tags: Tag*) extends WriteEventAdapter {

  def manifest(event: Any): String = ""

  def toJournal(event: Any): Any = {
    event match {
      case evt: Ev => Tagged(evt, Set(tags.map(_.value):_*))
      case evt => evt
    }
  }
}
