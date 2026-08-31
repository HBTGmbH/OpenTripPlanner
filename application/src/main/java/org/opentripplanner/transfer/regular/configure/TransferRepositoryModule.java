package org.opentripplanner.transfer.regular.configure;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;
import org.opentripplanner.framework.transaction.RepositoryRegistry;
import org.opentripplanner.framework.transaction.api.RepositoryHandle;
import org.opentripplanner.framework.transaction.configure.TransitDomain;
import org.opentripplanner.transfer.regular.TransferRepository;
import org.opentripplanner.transfer.regular.TransferRepositorySnapshot;
import org.opentripplanner.transfer.regular.internal.DefaultTransferRepository;
import org.opentripplanner.transfer.regular.internal.TransferRepositoryLifecycle;
import org.opentripplanner.transit.service.TransitRepository;

/**
 * Wires the regular (path) transfer repository into the transaction framework.
 * <p>
 * The {@link TransferRepository} itself is supplied as a Dagger {@code @BindsInstance} (the
 * deserialized instance at graph load, or a bare instance at graph build — see
 * {@link EmptyTransferRepositoryModule}). This module only registers it as a transactional
 * repository on the {@code @TransitDomain} {@link RepositoryRegistry}, alongside the timetable and
 * realtime-vehicle repositories. The {@code registerRepository} call freezes the initial snapshot
 * from the scheduled transfers — this is the seeding mechanism. The handle is what
 * {@code WriteContext} resolves for transfer updates and what the request scope resolves for reads.
 */
@Module
public class TransferRepositoryModule {

  @Provides
  @Singleton
  public static RepositoryHandle<TransferRepositorySnapshot, TransferRepository>
  transferRepositoryHandle(
    TransferRepository transferRepository,
    @TransitDomain RepositoryRegistry repositoryRegistry,
    TransitRepository transitRepository
  ) {
    // Capture the SiteRepository so the snapshot can derive transfersByStopIndex for the Raptor path.
    // The deserialized repo is already indexed (FlexTransferIndex throws if re-indexed), so we do
    // NOT call index() here — only setSiteRepository + registerRepository (which freezes the seed).
    ((DefaultTransferRepository) transferRepository)
      .setSiteRepository(transitRepository.getSiteRepository());
    return repositoryRegistry.registerRepository(
      transferRepository,
      new TransferRepositoryLifecycle()
    );
  }
}

