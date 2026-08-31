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
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.service.SiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultTransferRepository implements TransferRepository {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultTransferRepository.class);

  private final Multimap<StopLocation, PathTransfer> transfersByStop = HashMultimap.create();

  private final TransferIndex index;

  /**
   * Needed by {@link #freeze()} to derive {@link TransferRepositorySnapshot#transfersByStopIndex()}
   * for the Raptor path. Captured at registration time via {@link #setSiteRepository(SiteRepository)}
   * and deliberately not serialized — it is an application-scoped singleton, so storing it in the
   * serialized graph would duplicate it and risk identity mismatch.
   */
  private transient SiteRepository siteRepository;

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
   * Capture the {@link SiteRepository} so that {@link #freeze()} can derive the stop-indexed
   * transfer list for the Raptor path. Called once, at wiring time, before the initial snapshot is
   * frozen. Not serialized.
   */
  public void setSiteRepository(SiteRepository siteRepository) {
    this.siteRepository = siteRepository;
  }

  /**
   * Produce an immutable snapshot of the current state. Used by the repository lifecycle at commit,
   * and exposed publicly so tests can build a read view from a populated repository.
   */
  public TransferRepositorySnapshot freeze() {
    return new DefaultTransferRepositorySnapshot(
      ImmutableListMultimap.copyOf(transfersByStop),
      siteRepository
    );
  }
}
