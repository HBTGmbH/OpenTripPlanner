package org.opentripplanner.transfer.regular.internal;

import com.google.common.collect.ImmutableListMultimap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.transfer.regular.TransferRepositorySnapshot;
import org.opentripplanner.transfer.regular.model.PathTransfer;
import org.opentripplanner.transfer.regular.model.TransfersMapper;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.service.SiteRepository;

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
 * {@link #transfersByStopIndex()} is derived lazily (on first access) and memoized, since it is an
 * O(stops × transfers-per-stop) pass that not every reader needs.
 */
class DefaultTransferRepositorySnapshot implements TransferRepositorySnapshot {

  private final ImmutableListMultimap<StopLocation, PathTransfer> transfersByStop;

  /**
   * Captured at freeze time so {@link #transfersByStopIndex()} can derive the stop-indexed list via
   * {@link TransfersMapper#mapTransfers}. May be {@code null} in unit tests that only exercise the
   * multimap queries.
   */
  @Nullable
  private final SiteRepository siteRepository;

  /** Lazily derived and memoized. {@code null} until first computed. */
  private List<List<PathTransfer>> transfersByStopIndex;

  DefaultTransferRepositorySnapshot(
    ImmutableListMultimap<StopLocation, PathTransfer> transfersByStop,
    @Nullable SiteRepository siteRepository
  ) {
    this.transfersByStop = transfersByStop;
    this.siteRepository = siteRepository;
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
    if (transfersByStopIndex == null) {
      transfersByStopIndex =
        siteRepository == null
          ? List.of()
          : TransfersMapper.mapTransfers(siteRepository, toTransferRepositoryView());
    }
    return transfersByStopIndex;
  }

  /**
   * Used by the repository lifecycle: produce a mutable repository seeded with this snapshot's
   * state. The returned repository re-indexes itself (so Flex lookups work on the mutable copy).
   */
  DefaultTransferRepository copyOnWrite() {
    var repo = new DefaultTransferRepository(TransferIndexFactory.createIndex());
    repo.setSiteRepository(siteRepository);
    repo.addAllTransfersByStops(transfersByStop);
    repo.index();
    return repo;
  }

  /**
   * A thin adapter exposing this snapshot as a {@link
   * org.opentripplanner.transfer.regular.TransferRepository} so {@link TransfersMapper#mapTransfers}
   * can read its transfers by stop. Only the {@code findTransfersByStop} method is used by the
   * mapper.
   */
  private org.opentripplanner.transfer.regular.TransferRepository toTransferRepositoryView() {
    return new SnapshotAsRepository(transfersByStop);
  }

  /** Minimal read-only adapter for {@link TransfersMapper#mapTransfers}. */
  private record SnapshotAsRepository(
    ImmutableListMultimap<StopLocation, PathTransfer> transfersByStop
  )
    implements org.opentripplanner.transfer.regular.TransferRepository {
    @Override
    public Collection<PathTransfer> findTransfersByStop(StopLocation stop) {
      return transfersByStop.get(stop);
    }

    @Override
    public List<PathTransfer> findTransfersByMode(StreetMode mode) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Collection<PathTransfer> listPathTransfers() {
      return transfersByStop.values();
    }

    @Override
    public void addAllTransfersByStops(
      com.google.common.collect.Multimap<StopLocation, PathTransfer> transfersByStop
    ) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void index() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Collection<PathTransfer> findWalkTransfersToStop(StopLocation toStop) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Collection<PathTransfer> findWalkTransfersFromStop(StopLocation fromStop) {
      throw new UnsupportedOperationException();
    }
  }
}
