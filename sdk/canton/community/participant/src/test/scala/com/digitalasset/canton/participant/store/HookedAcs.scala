// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.store

import com.daml.lf.data.Ref.PackageId
import com.digitalasset.canton.RequestCounter
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.participant.store.ActiveContractSnapshot.ActiveContractIdsChange
import com.digitalasset.canton.participant.store.ActiveContractStore.{
  AcsError,
  AcsWarning,
  ContractState,
}
import com.digitalasset.canton.participant.store.HookedAcs.noFetchAction
import com.digitalasset.canton.participant.util.{StateChange, TimeOfChange}
import com.digitalasset.canton.protocol.{
  LfContractId,
  SourceDomainId,
  TargetDomainId,
  TransferDomainId,
}
import com.digitalasset.canton.pruning.{PruningPhase, PruningStatus}
import com.digitalasset.canton.store.IndexedStringStore
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.CheckedT

import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.SortedMap
import scala.concurrent.{ExecutionContext, Future}

private[participant] class HookedAcs(private val acs: ActiveContractStore)(implicit
    val ec: ExecutionContext
) extends ActiveContractStore {
  import HookedAcs.{noArchivePurgeAction, noCreateAddAction, noTransferAction}

  private val nextCreateAddHook
      : AtomicReference[(Seq[LfContractId], TimeOfChange) => Future[Unit]] =
    new AtomicReference[(Seq[LfContractId], TimeOfChange) => Future[Unit]](
      noCreateAddAction
    )
  private val nextArchivePurgeHook
      : AtomicReference[(Seq[LfContractId], TimeOfChange) => Future[Unit]] =
    new AtomicReference[(Seq[LfContractId], TimeOfChange) => Future[Unit]](noArchivePurgeAction)
  private val nextTransferHook =
    new AtomicReference[
      (
          Seq[(LfContractId, TransferDomainId, TimeOfChange)],
          Boolean,
      ) => Future[Unit]
    ](
      noTransferAction
    )
  private val nextFetchHook: AtomicReference[Iterable[LfContractId] => Future[Unit]] =
    new AtomicReference[Iterable[LfContractId] => Future[Unit]](noFetchAction)

  override private[store] def indexedStringStore: IndexedStringStore = acs.indexedStringStore

  def setCreateHook(
      preCreate: (Seq[LfContractId], TimeOfChange) => Future[Unit]
  ): Unit =
    nextCreateAddHook.set(preCreate)
  def setArchiveHook(preArchive: (Seq[LfContractId], TimeOfChange) => Future[Unit]): Unit =
    nextArchivePurgeHook.set(preArchive)
  def setTransferHook(
      preTransfer: (
          Seq[(LfContractId, TransferDomainId, TimeOfChange)],
          Boolean,
      ) => Future[Unit]
  ): Unit =
    nextTransferHook.set(preTransfer)
  def setFetchHook(preFetch: Iterable[LfContractId] => Future[Unit]): Unit =
    nextFetchHook.set(preFetch)

  override def markContractsCreatedOrAdded(
      contracts: Seq[LfContractId],
      toc: TimeOfChange,
      isCreation: Boolean,
  )(implicit
      traceContext: TraceContext
  ): CheckedT[Future, AcsError, AcsWarning, Unit] = CheckedT {
    val preCreate = nextCreateAddHook.getAndSet(noCreateAddAction)
    preCreate(contracts, toc).flatMap { _ =>
      acs.markContractsCreated(contracts, toc).value
    }
  }

  override def purgeOrArchiveContracts(
      contracts: Seq[LfContractId],
      toc: TimeOfChange,
      isArchival: Boolean,
  )(implicit
      traceContext: TraceContext
  ): CheckedT[Future, AcsError, AcsWarning, Unit] = CheckedT {
    val preArchive = nextArchivePurgeHook.getAndSet(noArchivePurgeAction)
    preArchive(contracts, toc)
      .flatMap { _ =>
        acs.archiveContracts(contracts, toc).value
      }
  }

  override def transferInContracts(
      transferIns: Seq[(LfContractId, SourceDomainId, TimeOfChange)]
  )(implicit
      traceContext: TraceContext
  ): CheckedT[Future, AcsError, AcsWarning, Unit] = CheckedT {
    val preTransfer = nextTransferHook.getAndSet(noTransferAction)
    preTransfer(
      transferIns,
      false,
    ).flatMap { _ =>
      acs.transferInContracts(transferIns).value
    }
  }

  override def transferOutContracts(
      transferOuts: Seq[(LfContractId, TargetDomainId, TimeOfChange)]
  )(implicit
      traceContext: TraceContext
  ): CheckedT[Future, AcsError, AcsWarning, Unit] = CheckedT {
    val preTransfer = nextTransferHook.getAndSet(noTransferAction)
    preTransfer(transferOuts, true).flatMap { _ =>
      acs.transferOutContracts(transferOuts).value
    }
  }

  override def fetchStates(
      contractIds: Iterable[LfContractId]
  )(implicit traceContext: TraceContext): Future[Map[LfContractId, ContractState]] = {
    val preFetch = nextFetchHook.getAndSet(noFetchAction)
    preFetch(contractIds).flatMap(_ => acs.fetchStates(contractIds))
  }

  override def fetchStatesForInvariantChecking(ids: Iterable[LfContractId])(implicit
      traceContext: TraceContext
  ): Future[Map[LfContractId, StateChange[ActiveContractStore.Status]]] =
    acs.fetchStatesForInvariantChecking(ids)

  override def snapshot(timestamp: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Future[SortedMap[LfContractId, CantonTimestamp]] =
    acs.snapshot(timestamp)

  override def snapshot(rc: RequestCounter)(implicit
      traceContext: TraceContext
  ): Future[SortedMap[LfContractId, RequestCounter]] =
    acs.snapshot(rc)

  override def contractSnapshot(contractIds: Set[LfContractId], timestamp: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Future[Map[LfContractId, CantonTimestamp]] =
    acs.contractSnapshot(contractIds, timestamp)

  override def doPrune(beforeAndIncluding: CantonTimestamp, lastPruning: Option[CantonTimestamp])(
      implicit traceContext: TraceContext
  ): Future[Int] =
    acs.doPrune(beforeAndIncluding, lastPruning: Option[CantonTimestamp])

  override protected[canton] def advancePruningTimestamp(
      phase: PruningPhase,
      timestamp: CantonTimestamp,
  )(implicit traceContext: TraceContext): Future[Unit] =
    acs.advancePruningTimestamp(phase, timestamp)

  override def pruningStatus(implicit
      traceContext: TraceContext
  ): Future[Option[PruningStatus]] =
    acs.pruningStatus

  override def deleteSince(criterion: RequestCounter)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    acs.deleteSince(criterion)

  override def contractCount(timestamp: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Future[Int] =
    acs.contractCount(timestamp)

  override def changesBetween(fromExclusive: TimeOfChange, toInclusive: TimeOfChange)(implicit
      traceContext: TraceContext
  ): Future[LazyList[(TimeOfChange, ActiveContractIdsChange)]] =
    acs.changesBetween(fromExclusive, toInclusive)

  override def packageUsage(pkg: PackageId, contractStore: ContractStore)(implicit
      traceContext: TraceContext
  ): Future[Option[LfContractId]] = acs.packageUsage(pkg, contractStore)
}

object HookedAcs {
  private val noCreateAddAction: (Seq[LfContractId], TimeOfChange) => Future[Unit] = { (_, _) =>
    Future.unit
  }

  private val noArchivePurgeAction: (Seq[LfContractId], TimeOfChange) => Future[Unit] = { (_, _) =>
    Future.unit
  }

  private val noTransferAction: (
      Seq[(LfContractId, TransferDomainId, TimeOfChange)],
      Boolean,
  ) => Future[Unit] = { (_, _) =>
    Future.unit
  }

  private val noFetchAction: Iterable[LfContractId] => Future[Unit] = _ => Future.unit
}
