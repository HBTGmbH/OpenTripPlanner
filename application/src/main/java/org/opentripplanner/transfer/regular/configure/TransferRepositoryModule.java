package org.opentripplanner.transfer.regular.configure;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;
import org.opentripplanner.framework.transaction.RepositoryRegistry;
import org.opentripplanner.framework.transaction.api.RepositoryHandle;
import org.opentripplanner.framework.transaction.configure.TransitDomain;
import org.opentripplanner.transfer.regular.TransferRepository;
import org.opentripplanner.transfer.regular.TransferRepositorySnapshot;
import org.opentripplanner.transfer.regular.internal.TransferRepositoryLifecycle;

/**
 * Wires the regular (path) transfer repository into the transaction framework.
 * <p>
 * The {@link TransferRepository} itself is supplied as a Dagger {@code @BindsInstance} (the
 * deserialized instance at graph load, or a bare instance at graph build — see
 * {@link EmptyTransferRepositoryModule}). This module only registers it as a transactional
 * repository on the {@code @TransitDomain} {@link RepositoryRegistry}, alongside the timetable and
 * realtime-vehicle repositories. The {@code registerRepository} call freezes the initial snapshot
 * from the scheduled transfers — but with {@code stopCount == 0}, so its {@code transfersByStopIndex}
 * is empty. That placeholder is corrected at startup by the initial {@code StopCountChangedEvent}
 * (published before the server serves requests), which keeps this module free of any
 * {@code SiteRepository} dependency. The handle is what {@code WriteContext} resolves for transfer
 * updates and what the request scope resolves for reads.
 */
@Module
public class TransferRepositoryModule {

  @Provides
  @Singleton
  public static RepositoryHandle<
    TransferRepositorySnapshot,
    TransferRepository
  > transferRepositoryHandle(
    TransferRepository transferRepository,
    @TransitDomain RepositoryRegistry repositoryRegistry
  ) {
    return repositoryRegistry.registerRepository(
      transferRepository,
      new TransferRepositoryLifecycle()
    );
  }
}
