package org.opentripplanner.transfer.regular.model;

import java.util.ArrayList;
import java.util.List;
import org.opentripplanner.transfer.regular.TransferRepository;
import org.opentripplanner.transit.model.site.RegularStop;

public class TransfersMapper {

  /**
   * Build the stop-indexed transfer list consumed by the Raptor path.
   *
   * @param stopCount          the number of stop indices (dense index space size); the returned
   *                           list has exactly this many slots
   * @param transferRepository source of the pre-calculated path transfers
   * @return a list where each element is the (immutable) list of transfers originating at the
   *         corresponding stop index
   */
  public static List<List<PathTransfer>> mapTransfers(
    int stopCount,
    TransferRepository transferRepository
  ) {
    // Pre-size with an empty slot for every stop index so the result aligns exactly with the dense
    // index space, regardless of which indices actually have transfers.
    List<List<PathTransfer>> transfersByStopIndex = new ArrayList<>(stopCount);
    for (int i = 0; i < stopCount; ++i) {
      transfersByStopIndex.add(new ArrayList<>());
    }

    for (PathTransfer pathTransfer : transferRepository.listPathTransfers()) {
      int fromIndex = pathTransfer.from.getIndex();
      // Guard against a from-stop whose index is outside the reported index space (e.g. an
      // unindexed sentinel, or a stop added after stopCount was captured).
      if (fromIndex < 0 || fromIndex >= stopCount) {
        continue;
      }
      if (pathTransfer.to instanceof RegularStop) {
        transfersByStopIndex.get(fromIndex).add(pathTransfer);
      }
    }

    // Compact and make the inner lists immutable, then return an immutable copy.
    return transfersByStopIndex.stream().<List<PathTransfer>>map(List::copyOf).toList();
  }
}
