package org.opentripplanner.transfer.regular.internal;

import org.opentripplanner.framework.transaction.api.RepositoryLifecycle;
import org.opentripplanner.transfer.regular.TransferRepository;
import org.opentripplanner.transfer.regular.TransferRepositorySnapshot;

/**
 * Copy-on-write / freeze lifecycle for the regular-transfer repository. Each transaction that
 * writes transfers gets a new mutable repository initialized from the last committed snapshot, and
 * a new immutable snapshot is published when the transaction commits. Edits made to a repository
 * that is never frozen are simply discarded — this supports transaction rollback in the future.
 */
public class TransferRepositoryLifecycle
  implements RepositoryLifecycle<TransferRepositorySnapshot, TransferRepository> {

  @Override
  public TransferRepository copyOnWrite(TransferRepositorySnapshot snapshot) {
    // the cast is safe: all snapshots are created by freeze() below
    return ((DefaultTransferRepositorySnapshot) snapshot).copyOnWrite();
  }

  @Override
  public TransferRepositorySnapshot freeze(TransferRepository repository) {
    // the cast is safe: all repositories are created by copyOnWrite() above or by the module
    return ((DefaultTransferRepository) repository).freeze();
  }
}
