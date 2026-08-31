package org.opentripplanner.transfer.regular;

import java.util.Collection;
import java.util.List;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.transfer.regular.model.PathTransfer;
import org.opentripplanner.transit.model.site.StopLocation;

/**
 * An immutable, read-only snapshot of the regular (path) transfers. A new snapshot is published
 * each time a transaction that touched the {@link TransferRepository} commits. Request threads read
 * a snapshot resolved at the start of the request, through the request-scoped
 * {@link RegularTransferService}.
 * <p>
 * This is the read view of both regular-transfer read paths:
 * <ul>
 *   <li>The {@link RegularTransferService} multimap queries (GraphQL transfers field, debug vector
 *       tiles, refetch, Flex access/egress).</li>
 *   <li>The Raptor path, via {@link #transfersByStopIndex()}.</li>
 * </ul>
 */
public interface TransferRepositorySnapshot {
  /**
   * @return all {@code PathTransfer}s with the specified {@code StopLocation} as a from-stop.
   */
  Collection<PathTransfer> findTransfersByStop(StopLocation fromStop);

  /**
   * @return all {@code PathTransfer}s valid for the given mode.
   */
  List<PathTransfer> findTransfersByMode(StreetMode mode);

  /**
   * @return all {@code PathTransfer}s in the snapshot.
   */
  Collection<PathTransfer> listPathTransfers();

  /**
   * @param toStop {@code StopLocation} that is set as a to-stop.
   * @return all walk-mode {@code PathTransfer}s with the specified {@code StopLocation} as a
   * to-stop.
   */
  Collection<PathTransfer> findWalkTransfersToStop(StopLocation toStop);

  /**
   * @param fromStop {@code StopLocation} that is set as a from-stop.
   * @return all walk-mode {@code PathTransfer}s with the specified {@code StopLocation} as a
   * from-stop.
   */
  Collection<PathTransfer> findWalkTransfersFromStop(StopLocation fromStop);

  /**
   * The regular transfers indexed by stop index: each element is the list of transfers whose
   * from-stop has that index. This is the derived form consumed by the Raptor router, built once
   * (lazily, on first access) per snapshot.
   *
   * @return an immutable {@code List<List<PathTransfer>>} indexed by stop index.
   */
  List<List<PathTransfer>> transfersByStopIndex();
}
