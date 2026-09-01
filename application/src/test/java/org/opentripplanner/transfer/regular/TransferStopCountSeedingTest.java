package org.opentripplanner.transfer.regular;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.framework.transaction.RepositoryRegistry;
import org.opentripplanner.framework.transaction.UpdateManager;
import org.opentripplanner.framework.transaction.api.RepositoryHandle;
import org.opentripplanner.framework.transaction.internal.TransactionFactory;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.transfer.regular.internal.StopCountChangedEventHandler;
import org.opentripplanner.transfer.regular.internal.TransferRepositoryLifecycle;
import org.opentripplanner.transfer.regular.model.PathTransfer;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.service.StopCountChangedEvent;

/**
 * End-to-end test of the startup seeding path: a {@link StopCountChangedEvent} published through
 * {@link UpdateManager#submitAndCommit} must make the rebuilt stop-indexed transfer list visible
 * immediately, even when the manager runs in periodic-commit mode (as the transit manager does).
 */
class TransferStopCountSeedingTest {

  private static final Duration NEVER = Duration.ofHours(1);

  private static PathTransfer transfer(int fromStop, int toStop) {
    var from = RegularStop.of(FeedScopedId.of("F", "S" + fromStop), () -> fromStop).build();
    var to = RegularStop.of(FeedScopedId.of("F", "S" + toStop), () -> toStop).build();
    return new PathTransfer(from, to, 100, List.of(), EnumSet.of(StreetMode.WALK));
  }

  private record Fixture(
    RepositoryRegistry registry,
    UpdateManager updateManager,
    RepositoryHandle<TransferRepositorySnapshot, TransferRepository> handle,
    PathTransfer transfer
  ) {
    List<List<PathTransfer>> currentIndex() {
      return handle.repositorySnapshot(registry.scope()).transfersByStopIndex();
    }
  }

  /** Registers a populated repo on a periodic manager whose scheduler effectively never fires. */
  private static Fixture setup() {
    var t0 = transfer(0, 1);
    Multimap<StopLocation, PathTransfer> byStop = HashMultimap.create();
    byStop.put(t0.from, t0);
    var repo = TransferServiceTestFactory.defaultTransferRepository();
    repo.addAllTransfersByStops(byStop);
    repo.index();

    var registry = TransactionFactory.createRepositoryRegistry();
    RepositoryHandle<TransferRepositorySnapshot, TransferRepository> handle =
      registry.registerRepository(repo, new TransferRepositoryLifecycle());

    var threadFactory = Thread.ofPlatform().name("seed-test").factory();
    var updateManager = TransactionFactory.createUpdateManagerWithPeriodicCommits(
      "seed-test",
      registry,
      threadFactory,
      NEVER
    );
    updateManager.register(new StopCountChangedEventHandler(), handle);
    return new Fixture(registry, updateManager, handle, t0);
  }

  @Test
  void submitAndCommitSeedsIndexImmediatelyUnderPeriodicMode() throws Exception {
    var f = setup();
    // Before the event the registration-time seed has stopCount 0, so the index is empty.
    assertThat(f.currentIndex()).isEmpty();

    f.updateManager.submitAndCommit(ctx -> ctx.publish(new StopCountChangedEvent(3))).get();

    var seeded = f.currentIndex();
    assertThat(seeded).hasSize(3);
    assertThat(seeded.get(0)).containsExactly(f.transfer);
    f.updateManager.shutdown();
  }

  @Test
  void plainSubmitDoesNotCommitUnderPeriodicMode() throws Exception {
    var f = setup();

    // Negative control: the task runs, but a periodic manager defers the commit, so the seed is
    // NOT visible yet (the scheduler is set to effectively never fire during the test).
    f.updateManager.submit(ctx -> ctx.publish(new StopCountChangedEvent(3))).get();

    assertThat(f.currentIndex()).isEmpty();
    f.updateManager.shutdown();
  }
}
