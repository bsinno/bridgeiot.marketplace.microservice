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
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Properties
import akka.Done
import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableMessage
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream._
import akka.stream.scaladsl.{RestartSource, Source}
import akka.stream.stage._

import io.circe.parser._
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory

class TopicDecoder[Msg](topicDesc: TopicDesc[Msg], name: String) extends GraphStage[FlowShape[Committable[String], Committable[Msg]]] {
  val in = Inlet[Committable[String]]("Decoder.in")
  val out = Outlet[Committable[Msg]]("Decoder.out")
  val shape = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with StageLogging {
    setHandler(in, new InHandler {
      override def onPush() = {
        if (isAvailable(out)) {
          val inMessage = grab(in)
          val rec = inMessage.record
          val msgStr = rec.value
          parse(msgStr) match {
            case Right(json) =>
              topicDesc.decoder.decodeJson(json) match {
                case Right(msg) =>
                  log.info(s"$name received $msg in ${rec.topic} at offset ${rec.offset}")
                  val outMessage = CommittableMessage(new ConsumerRecord(rec.topic, rec.partition, rec.offset, rec.key, msg), inMessage.committableOffset)
                  push(out, outMessage)
                case Left(decodingError) =>
                  log.info(s"$name Decoding error: ${decodingError.toString} for $msgStr in ${rec.topic} at offset ${rec.offset}")
                  inMessage.committableOffset.commitScaladsl()
                    .map {
                      getAsyncCallback[Done] { _ =>
                        log.info(s"$name committed $msgStr in ${rec.topic} at offset ${rec.offset}")
                        pull(in)
                      }.invoke
                    }
              }
            case Left(parsingError) =>
              log.info(s"$name Parsing error: ${parsingError.toString} for $msgStr in ${rec.topic} at offset ${rec.offset}")
              inMessage.committableOffset.commitScaladsl()
                .map {
                  getAsyncCallback[Done] { _ =>
                    log.info(s"$name committed $msgStr in ${rec.topic} at offset ${rec.offset}")
                    pull(in)
                  }.invoke
                }
          }
        } else
          log.warning("$name: out is not available")
      }
    })
    setHandler(out, new OutHandler {
      override def onPull() = {
        pull(in)
      }
    })
  }
}

object TopicSource {
  def apply[Msg](topicDesc: TopicDesc[Msg], name: String)(implicit system: ActorSystem, mat: ActorMaterializer) = {
    val KAFKA_HOST = Properties.envOrElse("KAFKA_HOST", "kafka")
    val KAFKA_PORT = Properties.envOrElse("KAFKA_PORT", "9092")

    val consumerSettings = ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers(s"$KAFKA_HOST:$KAFKA_PORT")
      .withGroupId(name)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

    val log = LoggerFactory.getLogger(this.getClass)
    log.info(s"$name connected as consumer to topic ${topicDesc.topic}")

    RestartSource.withBackoff(
        minBackoff = 3.seconds,
        maxBackoff = 30.seconds,
        randomFactor = 0.2
      ) { () =>
        Consumer.committableSource(consumerSettings, Subscriptions.topics(topicDesc.topic))
          .via(new TopicDecoder[Msg](topicDesc, name))
          .watchTermination() {
            case (consumerControl, futureDone) =>
              futureDone
                .flatMap { _ =>
                  consumerControl.shutdown()
                }
                .recoverWith { case _ => consumerControl.shutdown() }
          }

    }
  }
}
