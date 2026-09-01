package org.opentripplanner.transfer.regular.internal;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Multimap;
import java.util.Collection;
import java.util.List;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.transfer.regular.TransferRepository;
import org.opentripplanner.transfer.regular.TransferRepositorySnapshot;
import org.opentripplanner.transfer.regular.model.PathTransfer;
import org.opentripplanner.transfer.regular.model.TransfersMapper;
import org.opentripplanner.transit.model.site.StopLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultTransferRepository implements TransferRepository {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultTransferRepository.class);

  private final Multimap<StopLocation, PathTransfer> transfersByStop = HashMultimap.create();

  private final TransferIndex index;

  /**
   * The size of the dense stop-index space, needed by {@link #freeze()} to build
   * {@link TransferRepositorySnapshot#transfersByStopIndex()} for the Raptor path. Delivered via a
   * {@code StopCountChangedEvent} (and threaded across copy-on-write), so this repository has no
   * direct dependency on the {@code SiteRepository}. Deliberately not serialized — it is build-time
   * state that is always re-seeded by the initial event at startup.
   */
  private transient int stopCount = 0;

  public DefaultTransferRepository(TransferIndex index) {
    this.index = index;
  }

  @Override
  public Collection<PathTransfer> findTransfersByStop(StopLocation stop) {
    return transfersByStop.get(stop);
  }

  /** Pre-generated transfers between all stops filtered based on the modes in the PathTransfer. */
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
  public void addAllTransfersByStops(Multimap<StopLocation, PathTransfer> transfersByStop) {
    index.invalidate();
    this.transfersByStop.putAll(transfersByStop);
  }

  @Override
  public void index() {
    LOG.info("Transfer repository indexing...");
    index.index(this);
    LOG.info("Transfer repository indexing complete.");
  }

  @Override
  public Collection<PathTransfer> findWalkTransfersToStop(StopLocation toStop) {
    return index.findWalkTransfersToStop(toStop);
  }

  @Override
  public Collection<PathTransfer> findWalkTransfersFromStop(StopLocation fromStop) {
    return index.findWalkTransfersFromStop(fromStop);
  }

  /**
   * Set the size of the dense stop-index space used by {@link #freeze()} to build the stop-indexed
   * transfer list. Written by the {@code StopCountChangedEventHandler} inside a write transaction,
   * and by {@code DefaultTransferRepositorySnapshot#copyOnWrite()} to carry the value forward.
   */
  void setStopCount(int stopCount) {
    this.stopCount = stopCount;
  }

  int stopCount() {
    return stopCount;
  }

  /**
   * Produce an immutable snapshot of the current state. Used by the repository lifecycle at commit,
   * and exposed publicly so tests can build a read view from a populated repository. The stop-indexed
   * transfer list for the Raptor path is built eagerly here from the current {@link #stopCount}.
   */
  public TransferRepositorySnapshot freeze() {
    return new DefaultTransferRepositorySnapshot(
      ImmutableListMultimap.copyOf(transfersByStop),
      TransfersMapper.mapTransfers(stopCount, this),
      stopCount
    );
  }
}
