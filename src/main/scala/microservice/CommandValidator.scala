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
import scala.languageFeature.higherKinds
import akka.Done
import akka.stream.FanOutShape.{Init, Name}
import akka.stream._
import akka.stream.stage._

import org.apache.kafka.clients.consumer.ConsumerRecord

case class CommandValidatorShape[Cmd <: Command](init: Init[Committable[Cmd]] = Name[Committable[Cmd]]("CommandValidator"))
  extends FanOutShape[Committable[Cmd]](init) {
  protected override def construct(init: Init[Committable[Cmd]]) = CommandValidatorShape(init)

  val validatedCmds = newOutlet[Committable[Cmd]]("validatedCmds")
  val errors = newOutlet[Error]("errors")
}

case class CommandValidator[Cmd <: Command](validator: PartialFunction[Cmd, Either[Error, Cmd]], name: String) extends GraphStage[CommandValidatorShape[Cmd]] {
  val shape = CommandValidatorShape[Cmd]()

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with StageLogging {
      var validatedCmdDemand = false
      var errorDemand = false

      setHandler(shape.in, new InHandler {
        override def onPush() = {
          val inMessage = grab(shape.in)
          val rec = inMessage.record
          val cmd = rec.value

          def emitCmd(cmd: Cmd) = {
            validatedCmdDemand = false
            val outRec = new ConsumerRecord(rec.topic, rec.partition, rec.offset, rec.key, cmd)
            emit(shape.validatedCmds, inMessage.copy(record = outRec))
          }

          if (validator.isDefinedAt(cmd)) {
            validator(cmd) match {
              case Right(validatedCmd: Cmd) =>
                log.debug(s"$name validated: $cmd => $validatedCmd")
                emitCmd(validatedCmd)

              case Left(error: Error) =>
                errorDemand = false
                log.info(s"$name validation error $error for $cmd in ${rec.topic} at offset ${rec.offset}")
                inMessage.committableOffset.commitScaladsl()
                  .map {
                    getAsyncCallback[Done] { _ =>
                      log.debug(s"$name committed $cmd in ${rec.topic} at offset ${rec.offset}")
                      emit(shape.errors, error)
                    }.invoke
                  }
            }
          } else {
            log.debug(s"$name not validated: $cmd")
            emitCmd(cmd)
          }
        }
      })

      setHandler(shape.validatedCmds, new OutHandler {
        override def onPull(): Unit = {
          validatedCmdDemand = true
          if (validatedCmdDemand && errorDemand) {
            pull(shape.in)
          }
        }
      })

      setHandler(shape.errors, new OutHandler {
        override def onPull(): Unit = {
          errorDemand = true
          if (validatedCmdDemand && errorDemand) {
            pull(shape.in)
          }
        }
      })
    }
}
