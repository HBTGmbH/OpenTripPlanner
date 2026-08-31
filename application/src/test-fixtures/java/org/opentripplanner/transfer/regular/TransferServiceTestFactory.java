package org.opentripplanner.transfer.regular;

import org.opentripplanner.ext.flex.FlexTransferIndex;
import org.opentripplanner.transfer.regular.internal.DefaultTransferRepository;
import org.opentripplanner.transfer.regular.internal.DefaultTransferService;
import org.opentripplanner.transfer.regular.internal.TransferIndex;

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

  public static TransferRepository defaultTransferRepository() {
    return new DefaultTransferRepository(new TransferIndex());
  }

  public static TransferRepository withFlex() {
    return new DefaultTransferRepository(new FlexTransferIndex());
  }
}
