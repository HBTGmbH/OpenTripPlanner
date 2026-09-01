package org.opentripplanner.transfer.regular;

import org.opentripplanner.ext.flex.FlexTransferIndex;
import org.opentripplanner.transfer.regular.internal.DefaultTransferRepository;
import org.opentripplanner.transfer.regular.internal.DefaultTransferService;
import org.opentripplanner.transfer.regular.internal.StopCountChangedEventHandler;
import org.opentripplanner.transfer.regular.internal.TransferIndex;
import org.opentripplanner.transit.service.StopCountChangedEvent;

public class TransferServiceTestFactory {

  public static RegularTransferService defaultTransferService() {
    return transferService(defaultTransferRepository());
  }

  /**
   * Build a {@link RegularTransferService} backed by a frozen snapshot of the given repository.
   * Tests populate the repository (via {@code addAllTransfersByStops} + {@code index()}) and then
   * call this to obtain a read view, mirroring how the runtime request scope resolves a snapshot.
   */
  public static RegularTransferService transferService(TransferRepository transferRepository) {
    return new DefaultTransferService(((DefaultTransferRepository) transferRepository).freeze());
  }

  /**
   * As {@link #transferService(TransferRepository)}, but first seeds the stop count via the real
   * {@link StopCountChangedEventHandler} so the frozen snapshot's {@code transfersByStopIndex()} is
   * populated (sized to {@code stopCount}). Use in tests that exercise the Raptor stop-indexed view.
   * //TODO Max: maybe remove the 1-arg overloaded method, so all tests have to supply a stopcount
   */
  public static RegularTransferService transferService(
    TransferRepository transferRepository,
    int stopCount
  ) {
    new StopCountChangedEventHandler().handle(
      new StopCountChangedEvent(stopCount),
      transferRepository
    );
    return transferService(transferRepository);
  }

  public static TransferRepository defaultTransferRepository() {
    return new DefaultTransferRepository(new TransferIndex());
  }

  public static TransferRepository withFlex() {
    return new DefaultTransferRepository(new FlexTransferIndex());
  }
}
