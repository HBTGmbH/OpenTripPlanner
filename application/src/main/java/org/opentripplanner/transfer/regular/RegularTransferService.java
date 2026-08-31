package org.opentripplanner.transfer.regular;

import java.util.Collection;
import java.util.List;
import org.opentripplanner.transfer.regular.model.PathTransfer;
import org.opentripplanner.transit.model.site.StopLocation;

/**
 * Access regular (path) transfers during OTP server runtime. It provides a frozen view of all
 * transfers at a point in time — the {@link TransferRepositorySnapshot} resolved for the current
 * request — which is not affected by ongoing transfer updates, allowing results to remain stable
 * over the course of a request.
 * <p>
 * This serves both regular-transfer read paths: the multimap queries below (GraphQL transfers
 * field, debug vector tiles, refetch, Flex access/egress) and the Raptor path via
 * {@link #transfersByStopIndex()}.
 */
public interface RegularTransferService {
  /**
   * @param fromStop {@code StopLocation} that is set as a from-stop
   * @return all {@code PathTransfer}s with the specified {@code StopLocation} as a from-stop
   */
  Collection<PathTransfer> findTransfersByStop(StopLocation fromStop);

  /**
   * @param fromStop {@code StopLocation} that is set as a from-stop
   * @return all walk mode {@code PathTransfer}s with the specified {@code StopLocation} as a
   * from-stop
   * @throws UnsupportedOperationException if flex routing is not activated
   * @throws IllegalStateException         if the index was not initialized
   */
  Collection<PathTransfer> findWalkTransfersFromStop(StopLocation fromStop);

  /**
   * @param toStop {@code StopLocation} that is set as a to-stop
   * @return all walk mode {@code PathTransfer}s with the specified {@code StopLocation} as a
   * to-stop
   * @throws UnsupportedOperationException if flex routing is not activated
   * @throws IllegalStateException         if the index was not initialized
   */
  Collection<PathTransfer> findWalkTransfersToStop(StopLocation toStop);

  /**
   * The regular transfers indexed by stop index, for the Raptor router. Each element is the list of
   * transfers whose from-stop has that index. Derived once (lazily) from the request's transfer
   * snapshot.
   *
   * @return an immutable {@code List<List<PathTransfer>>} indexed by stop index
   */
  List<List<PathTransfer>> transfersByStopIndex();
}
