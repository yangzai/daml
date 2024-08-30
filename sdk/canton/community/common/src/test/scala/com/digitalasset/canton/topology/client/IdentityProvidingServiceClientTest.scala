// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.topology.client

import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.topology.client.PartyTopologySnapshotClient.PartyInfo
import com.digitalasset.canton.topology.transaction.{ParticipantAttributes, ParticipantPermission}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, LfPartyId}
import org.scalatest.wordspec.AsyncWordSpec

import scala.Ordered.orderingToOrdered
import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}

class PartyTopologySnapshotClientTest extends AsyncWordSpec with BaseTest {

  import DefaultTestIdentities.*

  "party topology snapshot client" should {
    lazy val topology = Map(
      party1.toLf -> PartyInfo.nonConsortiumPartyInfo(
        Map(
          participant1 -> ParticipantAttributes(ParticipantPermission.Submission),
          participant2 -> ParticipantAttributes(ParticipantPermission.Observation),
        )
      ),
      party2.toLf -> PartyInfo.nonConsortiumPartyInfo(
        Map(
          participant2 -> ParticipantAttributes(ParticipantPermission.Observation)
        )
      ),
    )
    lazy val client = new PartyTopologySnapshotClient
      with BaseTopologySnapshotClient
      with PartyTopologySnapshotBaseClient {
      override def activeParticipantsOf(
          party: LfPartyId
      )(implicit traceContext: TraceContext): Future[Map[ParticipantId, ParticipantAttributes]] =
        Future.successful(
          topology.get(party).fold(Map.empty[ParticipantId, ParticipantAttributes])(_.participants)
        )
      override protected implicit def executionContext: ExecutionContext =
        PartyTopologySnapshotClientTest.this.executionContext
      override def timestamp: CantonTimestamp = ???
      override def inspectKnownParties(
          filterParty: String,
          filterParticipant: String,
      )(implicit traceContext: TraceContext): Future[Set[PartyId]] =
        ???

      override def activeParticipantsOfParties(
          parties: Seq[LfPartyId]
      )(implicit traceContext: TraceContext): Future[Map[LfPartyId, Set[ParticipantId]]] = ???

      override def activeParticipantsOfPartiesWithInfo(
          parties: Seq[LfPartyId]
      )(implicit
          traceContext: TraceContext
      ): Future[Map[LfPartyId, PartyInfo]] =
        Future.successful(
          parties.map { party =>
            party -> topology.getOrElse(party, PartyInfo.EmptyPartyInfo)
          }.toMap
        )

      /** Returns the Authority-Of delegations for consortium parties. Non-consortium parties delegate to themselves
        * with threshold one
        */
      override def authorityOf(
          parties: Set[LfPartyId]
      )(implicit
          traceContext: TraceContext
      ): Future[PartyTopologySnapshotClient.AuthorityOfResponse] =
        Future.successful(PartyTopologySnapshotClient.AuthorityOfResponse(Map.empty))

      override def partiesWithGroupAddressing(parties: Seq[LfPartyId])(implicit
          traceContext: TraceContext
      ): Future[Set[LfPartyId]] =
        ???

      override def activeParticipantsOfPartiesWithGroupAddressing(
          parties: Seq[LfPartyId]
      )(implicit traceContext: TraceContext): Future[Map[LfPartyId, Set[ParticipantId]]] =
        ???

      override def consortiumThresholds(
          parties: Set[LfPartyId]
      )(implicit traceContext: TraceContext): Future[Map[LfPartyId, PositiveInt]] = ???

      override def canNotSubmit(
          participant: ParticipantId,
          parties: Seq[LfPartyId],
      )(implicit traceContext: TraceContext): Future[immutable.Iterable[LfPartyId]] = ???
    }

    "allHaveActiveParticipants should yield correct results" in {
      for {
        right1 <- client.allHaveActiveParticipants(Set(party1.toLf)).value
        right2 <- client.allHaveActiveParticipants(Set(party1.toLf, party2.toLf)).value
        left1 <- client.allHaveActiveParticipants(Set(party1.toLf, party2.toLf), _.canConfirm).value
        left2 <- client.allHaveActiveParticipants(Set(party1.toLf, party3.toLf)).value
        left3 <- client.allHaveActiveParticipants(Set(party3.toLf)).value
      } yield {
        right1 shouldBe Right(())
        right2 shouldBe Right(())
        left1.left.value shouldBe a[Set[_]]
        left2.left.value shouldBe a[Set[_]]
        left3.left.value shouldBe a[Set[_]]
      }
    }

    "allHostedOn should yield correct results" in {
      for {
        yes1 <- client.allHostedOn(Set(party1.toLf), participant1)
        yes2 <- client.allHostedOn(Set(party1.toLf), participant2)
        no1 <- client.allHostedOn(Set(party1.toLf), participant2, _.permission.canConfirm)
        no2 <- client.allHostedOn(Set(party1.toLf, party3.toLf), participant1)
        no3 <- client.allHostedOn(
          Set(party1.toLf, party2.toLf),
          participant2,
          _.permission.canConfirm,
        )
        yes3 <- client.allHostedOn(
          Set(party1.toLf, party2.toLf),
          participant2,
          _.permission >= ParticipantPermission.Observation,
        )
      } yield {
        yes1 shouldBe true
        yes2 shouldBe true
        yes3 shouldBe true
        no1 shouldBe false
        no2 shouldBe false
        no3 shouldBe false
      }
    }

    "canConfirm should yield correct results" in {
      for {
        yes1 <- client.canConfirm(participant1, Set(party1.toLf))
        no1 <- client.canConfirm(participant1, Set(party2.toLf))
        no2 <- client.canConfirm(participant2, Set(party1.toLf))
      } yield {
        yes1 shouldBe Set(party1.toLf)
        no1 shouldBe Set.empty
        no2 shouldBe Set.empty
      }
    }
  }

}
