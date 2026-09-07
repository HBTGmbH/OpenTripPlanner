package org.opentripplanner.transfer.regular;

import com.google.common.collect.Multimap;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.transfer.regular.model.PathTransfer;
import org.opentripplanner.transit.model.site.StopLocation;

/**
 * The mutable, write-side repository for regular (path) transfers. It is populated during the graph
 * build process and saved into the serialized graph object, and — at runtime — is managed by the
 * transaction framework: a new repository initialized from the last committed
 * {@link TransferRepositorySnapshot} is created for each write transaction, and the lifecycle
 * publishes a new immutable snapshot at commit.
 * <p>
 * It should only be accessed directly during graph building; request threads read a
 * {@link TransferRepositorySnapshot} through the request-scoped {@link RegularTransferService}. The
 * only read methods declared here are the two the graph-build path needs before any snapshot exists
 * ({@link #listPathTransfers()} and {@link #findTransfersByMode(StreetMode)}); all other reads live
 * on {@link TransferRepositorySnapshot}.
 */
public interface TransferRepository extends Serializable {
  /**
   * All {@code PathTransfer}s in the repository. Read at graph-build time (e.g. by
   * {@code TransfersMapper} to build the Raptor stop-indexed list); runtime reads use the snapshot.
   */
  Collection<PathTransfer> listPathTransfers();

  /**
   * All {@code PathTransfer}s valid for the given mode. Read at graph-build time (e.g. by the Flex
   * transfer index and {@code DirectTransferGenerator}); runtime reads use the snapshot.
   */
  List<PathTransfer> findTransfersByMode(StreetMode mode);

  /**
   * This is called to fill the repository with data. Calling this method results in invalidating
   * the index. For full functionality {@link #index()} has to be called after.
   *
   * @param transfersByStop transfers to be added, grouped by the stop set as from-stop
   */
  void addAllTransfersByStops(Multimap<StopLocation, PathTransfer> transfersByStop);

  /**
   * Initialize the index.
   */
  void index();

  /**
   * Set the size of the dense stop-index space, which determines the size of the stop-indexed
   * transfer list ({@code transfersByStopIndex}) built for the Raptor path when the repository is
   * frozen into a snapshot. Build/write-time only: set via a {@code StopCountChangedEvent} within a
   * write transaction (and carried forward on copy-on-write), so the transfer repository learns the
   * stop count without depending on the {@code SiteRepository}.
   */
  void setStopCount(int stopCount);
}
