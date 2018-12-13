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

import scala.concurrent.duration._
import scala.util.Properties
import akka.actor.ActorSystem
import akka.http.scaladsl.model.DateTime
import akka.kafka.scaladsl.Producer
import akka.kafka.{ProducerMessage, ProducerSettings}
import akka.stream._
import akka.stream.scaladsl.{Flow, RestartSink}

import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory

object CommittingTopicSink {
  val log = LoggerFactory.getLogger(this.getClass)

  def apply[Cmd <: Command](topicDesc: TopicDesc[Cmd], name: String)(implicit system: ActorSystem, mat: ActorMaterializer) = {
    val KAFKA_HOST = Properties.envOrElse("KAFKA_HOST", "kafka")
    val KAFKA_PORT = Properties.envOrElse("KAFKA_PORT", "9092")

    val topic = topicDesc.topic

    lazy val producerSettings = ProducerSettings(system, new StringSerializer, new StringSerializer)
      .withBootstrapServers(s"$KAFKA_HOST:$KAFKA_PORT")

    log.info(s"$name connected as producer to $topic")

    val topicEncoder = Flow[Committable[Cmd]]
      .map { inMsg =>
        val rec = inMsg.record
        val cmd = rec.value
        try {
          val outMsg = ProducerMessage.Message(new ProducerRecord[String, String](topic, 0, DateTime.now.clicks, null,
            topicDesc.encoder(cmd).noSpaces), inMsg.committableOffset)
          log.info(s"$name sent $cmd to $topic, committing ${rec.topic} at offset ${rec.offset}")
          outMsg
        } catch {
          case e: Throwable =>
            log.error(s"$name $topic: error: $e")
            null
        }
      }

    RestartSink.withBackoff(
      minBackoff = 3.seconds,
      maxBackoff = 30.seconds,
      randomFactor = 0.2
    ) { () =>
      topicEncoder
        .to(Producer.commitableSink(producerSettings))
    }
  }

}
