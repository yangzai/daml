// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf.codegen.backend.java

import com.google.protobuf.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import ut.retro.{InterfaceRetro, TemplateRetro}

import java.time.Instant
import scala.jdk.CollectionConverters._

final class InterfaceSpec extends AnyWordSpec with Matchers {

  "decoded contracts" should {
    import com.daml.ledger.javaapi.data.CreatedEvent
    import com.daml.ledger.javaapi.data.codegen.{Contract, DamlRecord, InterfaceCompanion}
    import ut.retro.TokenView

    import java.util.Collections.{emptyList, emptyMap}
    import java.util.Optional

    "roundtrip through CreatedEvent" in {
      def roundtrip[Id, View](
          data: DamlRecord[View],
          ic: InterfaceCompanion[_, Id, View],
      ): Contract[Id, View] =
        ic.fromCreatedEvent(
          new CreatedEvent(
            emptyList,
            "e",
            TemplateRetro.TEMPLATE_ID,
            "c",
            new TemplateRetro("", "", 0).toValue,
            ByteString.EMPTY,
            Map(ic.TEMPLATE_ID -> data.toValue).asJava,
            emptyMap,
            Optional.empty,
            emptyList,
            emptyList,
            Instant.EPOCH,
          )
        )
      val data = new TokenView("foobar", 12345)
      val contract = roundtrip(data, InterfaceRetro.INTERFACE)
      contract.data should ===(data)
      contract.getContractTypeId should ===(InterfaceRetro.TEMPLATE_ID)
    }
  }
}
