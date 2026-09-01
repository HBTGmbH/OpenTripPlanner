package org.opentripplanner.transfer.regular.internal;

import com.google.common.collect.ImmutableListMultimap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.transfer.regular.TransferRepositorySnapshot;
import org.opentripplanner.transfer.regular.model.PathTransfer;
import org.opentripplanner.transit.model.site.StopLocation;

/**
 * Immutable snapshot of the regular (path) transfers, published at commit time. A new instance is
 * produced by {@link DefaultTransferRepository#freeze()} each time a transaction that touched the
 * repository commits.
 * <p>
 * The walk-transfer lookups ({@link #findWalkTransfersFromStop} / {@link #findWalkTransfersToStop})
 * are derived by filtering the transfer multimap by {@link StreetMode#WALK}, exactly as
 * {@code FlexTransferIndex.index()} builds its index — so the snapshot and the build-time Flex index
 * agree on the same walk transfers without the snapshot needing to carry Flex-specific state.
 * <p>
 * {@link #transfersByStopIndex()} is built eagerly at freeze time (see
 * {@link DefaultTransferRepository#freeze()}) and stored here directly, so the snapshot is fully
 * self-contained and carries no reference to the {@code SiteRepository}.
 */
class DefaultTransferRepositorySnapshot implements TransferRepositorySnapshot {

  private final ImmutableListMultimap<StopLocation, PathTransfer> transfersByStop;

  /** Built eagerly at freeze time from {@link #transfersByStop} and {@link #stopCount}. */
  private final List<List<PathTransfer>> transfersByStopIndex;

  /**
   * The dense stop-index space size this snapshot was built with, carried forward to the mutable
   * repository on {@link #copyOnWrite()} so an edit-only transaction (which receives no
   * {@code StopCountChangedEvent}) still freezes with the correct stop count.
   */
  private final int stopCount;

  DefaultTransferRepositorySnapshot(
    ImmutableListMultimap<StopLocation, PathTransfer> transfersByStop,
    List<List<PathTransfer>> transfersByStopIndex,
    int stopCount
  ) {
    this.transfersByStop = transfersByStop;
    this.transfersByStopIndex = transfersByStopIndex;
    this.stopCount = stopCount;
  }

  @Override
  public Collection<PathTransfer> findTransfersByStop(StopLocation fromStop) {
    return transfersByStop.get(fromStop);
  }

  @Override
  public List<PathTransfer> findTransfersByMode(StreetMode mode) {
    return transfersByStop
      .values()
      .stream()
      .filter(pathTransfer -> pathTransfer.getModes().contains(mode))
      .toList();
  }

  @Override
  public Collection<PathTransfer> listPathTransfers() {
    return transfersByStop.values();
  }

  @Override
  public Collection<PathTransfer> findWalkTransfersToStop(StopLocation toStop) {
    List<PathTransfer> result = new ArrayList<>();
    for (PathTransfer transfer : transfersByStop.values()) {
      if (transfer.getModes().contains(StreetMode.WALK) && toStop.equals(transfer.to)) {
        result.add(transfer);
      }
    }
    return result;
  }

  @Override
  public Collection<PathTransfer> findWalkTransfersFromStop(StopLocation fromStop) {
    List<PathTransfer> result = new ArrayList<>();
    for (PathTransfer transfer : transfersByStop.get(fromStop)) {
      if (transfer.getModes().contains(StreetMode.WALK)) {
        result.add(transfer);
      }
    }
    return result;
  }

  @Override
  public List<List<PathTransfer>> transfersByStopIndex() {
    return transfersByStopIndex;
  }

  /**
   * Used by the repository lifecycle: produce a mutable repository seeded with this snapshot's
   * state. The returned repository re-indexes itself (so Flex lookups work on the mutable copy) and
   * carries the {@link #stopCount} forward so a subsequent freeze rebuilds the stop-indexed list
   * correctly even without a new {@code StopCountChangedEvent}.
   */
  DefaultTransferRepository copyOnWrite() {
    var repo = new DefaultTransferRepository(TransferIndexFactory.createIndex());
    repo.setStopCount(stopCount);
    repo.addAllTransfersByStops(transfersByStop);
    repo.index();
    return repo;
  }
}
