package org.opentripplanner.transfer.regular.internal;

import java.util.Collection;
import java.util.List;
import org.opentripplanner.transfer.regular.RegularTransferService;
import org.opentripplanner.transfer.regular.TransferRepositorySnapshot;
import org.opentripplanner.transfer.regular.model.PathTransfer;
import org.opentripplanner.transit.model.site.StopLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read access to regular transfers, backed by the request's {@link TransferRepositorySnapshot}.
 * One instance per request, constructed in {@code RequestScopedModule} from the snapshot resolved
 * under the request's {@code TransactionScope}, so every read over the request's lifetime sees the
 * same consistent transfer view.
 */
public class DefaultTransferService implements RegularTransferService {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultTransferService.class);

  private final TransferRepositorySnapshot snapshot;

  public DefaultTransferService(TransferRepositorySnapshot snapshot) {
    this.snapshot = snapshot;
    LOG.info("Initializing TransferService");
  }

  @Override
  public Collection<PathTransfer> findTransfersByStop(StopLocation fromStop) {
    return snapshot.findTransfersByStop(fromStop);
  }

  @Override
  public Collection<PathTransfer> findWalkTransfersToStop(StopLocation toStop) {
    return snapshot.findWalkTransfersToStop(toStop);
  }

  @Override
  public Collection<PathTransfer> findWalkTransfersFromStop(StopLocation fromStop) {
    return snapshot.findWalkTransfersFromStop(fromStop);
  }

  @Override
  public List<List<PathTransfer>> transfersByStopIndex() {
    return snapshot.transfersByStopIndex();
  }
}
