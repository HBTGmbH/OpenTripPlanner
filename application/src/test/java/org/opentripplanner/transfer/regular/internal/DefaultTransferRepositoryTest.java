package org.opentripplanner.transfer.regular.internal;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.transfer.regular.model.PathTransfer;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.service.StopCountChangedEvent;

class DefaultTransferRepositoryTest {

  private static PathTransfer transfer(int fromStop, int toStop) {
    var from = RegularStop.of(FeedScopedId.of("F", "S" + fromStop), () -> fromStop).build();
    var to = RegularStop.of(FeedScopedId.of("F", "S" + toStop), () -> toStop).build();
    return new PathTransfer(from, to, 100, List.of(), EnumSet.of(StreetMode.WALK));
  }

  private static DefaultTransferRepository repoWith(PathTransfer... transfers) {
    Multimap<StopLocation, PathTransfer> byStop = HashMultimap.create();
    for (var t : transfers) {
      byStop.put(t.from, t);
    }
    var repo = new DefaultTransferRepository(new TransferIndex());
    repo.addAllTransfersByStops(byStop);
    repo.index();
    return repo;
  }

  @Test
  void freezeBuildsEagerIndexSizedToStopCount() {
    var t0 = transfer(0, 1);
    var repo = repoWith(t0);
    repo.setStopCount(3);

    var snapshot = repo.freeze();

    assertThat(snapshot.transfersByStopIndex()).hasSize(3);
    assertThat(snapshot.transfersByStopIndex().get(0)).containsExactly(t0);
    assertThat(snapshot.transfersByStopIndex().get(1)).isEmpty();
    // Built eagerly and stored: the same instance is returned on every call.
    assertThat(snapshot.transfersByStopIndex()).isSameInstanceAs(snapshot.transfersByStopIndex());
  }

  @Test
  void handlerSetsStopCountReflectedInFreeze() {
    var repo = repoWith(transfer(0, 1));

    new StopCountChangedEventHandler().handle(new StopCountChangedEvent(2), repo);

    assertThat(repo.stopCount()).isEqualTo(2);
    assertThat(repo.freeze().transfersByStopIndex()).hasSize(2);
  }

  @Test
  void copyOnWritePreservesStopCountAcrossEditOnlyTransaction() {
    var repo = repoWith(transfer(0, 1));
    repo.setStopCount(4);
    var lifecycle = new TransferRepositoryLifecycle();

    var snapshot1 = lifecycle.freeze(repo);

    // Edit-only transaction: copy-on-write, add a transfer, freeze again WITHOUT a new event.
    var mutable = lifecycle.copyOnWrite(snapshot1);
    var extra = transfer(2, 3);
    Multimap<StopLocation, PathTransfer> more = HashMultimap.create();
    more.put(extra.from, extra);
    mutable.addAllTransfersByStops(more);
    mutable.index();
    var snapshot2 = lifecycle.freeze(mutable);

    // stopCount survived copy-on-write, so the rebuilt index is still sized to 4.
    assertThat(snapshot2.transfersByStopIndex()).hasSize(4);
    assertThat(snapshot2.transfersByStopIndex().get(2)).containsExactly(extra);
  }
}
