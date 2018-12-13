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

import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableMessage
import akka.stream._
import akka.stream.scaladsl.GraphDSL.Implicits._
import akka.stream.scaladsl.{Flow, GraphDSL, Merge, RunnableGraph}
import akka.stream.stage._
import akka.{Done, NotUsed}

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory

class EventAdapter[Cmd](adapter: PartialFunction[Event, List[Cmd]], name: String) extends
  GraphStage[FlowShape[Committable[Event], Committable[Cmd]]] {

  val in = Inlet[Committable[Event]]("EventAdapter.in")
  val out = Outlet[Committable[Cmd]]("EventAdapter.out")
  val shape = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with StageLogging {
      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          if (isAvailable(out)) {
            val inMessage = grab(in)
            val rec = inMessage.record
            val event = rec.value

            if (adapter.isDefinedAt(event)) {
              val cmds = adapter(event)
              log.info(s"$name adapting $event in ${rec.topic} at offset ${rec.offset} -> $cmds")
              val outMessages = cmds map { cmd =>
                CommittableMessage(new ConsumerRecord(rec.topic, rec.partition, rec.offset, rec.key, cmd), inMessage.committableOffset)
              }
              if (outMessages.isEmpty) {
                log.info(s"$name resulted in empty command list: $event in ${rec.topic} at offset ${rec.offset}")
                inMessage.committableOffset.commitScaladsl()
                  .map {
                    getAsyncCallback[Done] { _ =>
                      log.info(s"$name committed $event in ${rec.topic} at offset ${rec.offset}")
                      pull(in)
                    }.invoke
                  }
              } else
                emitMultiple(out, outMessages)
            } else {
              log.info(s"$name ignored $event in ${rec.topic} at offset ${rec.offset}")
              inMessage.committableOffset.commitScaladsl()
                .map {
                  getAsyncCallback[Done] { _ =>
                    log.info(s"$name committed $event in ${rec.topic} at offset ${rec.offset}")
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

object EventAdapterStage {
  def resetMeta[E <: Event] =
    Flow[Committable[E]]
      .map(_.asInstanceOf[Committable[Event]])
      .map { committable =>
        val meta = committable.record.value.meta
        meta.delay = Math.max(0, meta.delay - 1)
        committable
      }

  def topicSource[E <: Event](topic: TopicDesc[E], name: String)
                             (implicit system: ActorSystem, mat: ActorMaterializer, b: GraphDSL.Builder[NotUsed]) = {
    TopicSource(topic, name) ~> resetMeta[E]
  }

  val log = LoggerFactory.getLogger(this.getClass)

  def apply[Cmd <: Command](serviceName: String, eventTopics: List[TopicDesc[_ <: Event]], cmdTopic: TopicDesc[Cmd], adapter: PartialFunction[Event, List[Cmd]])
                           (implicit system: ActorSystem, mat: ActorMaterializer) = {
    RunnableGraph.fromGraph(GraphDSL.create() { implicit b: GraphDSL.Builder[NotUsed] =>
      val name = serviceName + "Adapter" + envSuffix
      val eventAdapter = b.add(new EventAdapter(adapter, name))
      val cmdTopicSink = b.add(CommittingTopicSink(cmdTopic, name))

      if (eventTopics.tail.isEmpty) {
        log.debug(s"$name directly connecting TopicSource to EventAdapter")
        topicSource(eventTopics.head, name) ~> eventAdapter ~> cmdTopicSink
      } else {
        val merge = b.add(Merge[Committable[Event]](eventTopics.length))
        eventTopics.view.zipWithIndex foreach { case (eventTopic, idx) =>
          topicSource(eventTopic, name) ~> merge.in(idx)
        }
        merge.out ~> eventAdapter ~> cmdTopicSink
      }
      ClosedShape
    })
  }
}
