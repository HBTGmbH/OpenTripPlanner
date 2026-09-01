package org.opentripplanner.transfer.regular.model;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.transfer.regular.TransferRepository;
import org.opentripplanner.transfer.regular.TransferServiceTestFactory;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.site.StopLocation;

class TransfersMapperTest {

  private static PathTransfer transfer(int fromStop, int toStop) {
    var from = RegularStop.of(FeedScopedId.of("F", "S" + fromStop), () -> fromStop).build();
    var to = RegularStop.of(FeedScopedId.of("F", "S" + toStop), () -> toStop).build();
    return new PathTransfer(from, to, 100, List.of(), EnumSet.of(StreetMode.WALK));
  }

  private static TransferRepository repoWith(PathTransfer... transfers) {
    Multimap<StopLocation, PathTransfer> byStop = HashMultimap.create();
    for (var t : transfers) {
      byStop.put(t.from, t);
    }
    var repo = TransferServiceTestFactory.defaultTransferRepository();
    repo.addAllTransfersByStops(byStop);
    repo.index();
    return repo;
  }

  @Test
  void alignsTransfersByFromStopIndexWithEmptySlots() {
    var t0 = transfer(0, 1);
    var t2 = transfer(2, 0);

    var result = TransfersMapper.mapTransfers(3, repoWith(t0, t2));

    assertThat(result).hasSize(3);
    assertThat(result.get(0)).containsExactly(t0);
    assertThat(result.get(1)).isEmpty();
    assertThat(result.get(2)).containsExactly(t2);
  }

  @Test
  void skipsTransfersWhoseFromIndexIsOutOfRange() {
    var inRange = transfer(1, 0);
    // from-index 5 is >= stopCount 3: must be dropped, not throw IndexOutOfBounds
    var outOfRange = transfer(5, 0);

    var result = TransfersMapper.mapTransfers(3, repoWith(inRange, outOfRange));

    assertThat(result).hasSize(3);
    assertThat(result.get(1)).containsExactly(inRange);
    assertThat(result.stream().mapToInt(List::size).sum()).isEqualTo(1);
  }

  @Test
  void emptyResultWhenStopCountIsZero() {
    var result = TransfersMapper.mapTransfers(0, repoWith(transfer(0, 1)));
    assertThat(result).isEmpty();
  }
}
