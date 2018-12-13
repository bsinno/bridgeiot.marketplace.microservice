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

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Source, SourceQueue}
import akka.stream.{ActorMaterializer, OverflowStrategy}

object SourceQueue {
  def apply[Msg] = Source.queue[Msg](100, OverflowStrategy.backpressure)
}

object MessageQueue {
  def apply[Msg](topicDesc: TopicDesc[Msg], name: String)(implicit system: ActorSystem, mat: ActorMaterializer): SourceQueue[Msg] = {
     SourceQueue[Msg]
      .to(TopicSink(topicDesc, name))
      .run
  }
}
