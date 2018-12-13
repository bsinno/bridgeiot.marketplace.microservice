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

import java.nio.ByteBuffer
import java.util.{Base64, UUID}
import javax.crypto.spec.SecretKeySpec

import pdi.jwt.JwtAlgorithm

object security {

  def algorithm = JwtAlgorithm.HS256
  def decodeBase64(str: String) = new SecretKeySpec(Base64.getUrlDecoder.decode(str), algorithm.fullName)

  def generateSecret: String = {
    val uuid = UUID.randomUUID()
    val bb = ByteBuffer.wrap(Array.ofDim[Byte](16))
    bb.putLong(uuid.getMostSignificantBits)
    bb.putLong(uuid.getLeastSignificantBits)
    Base64.getUrlEncoder.encodeToString(bb.array())
  }

}
