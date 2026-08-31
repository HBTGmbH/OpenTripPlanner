package org.opentripplanner.transfer.regular.configure;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;
import org.opentripplanner.transfer.regular.TransferRepository;
import org.opentripplanner.transfer.regular.internal.DefaultTransferRepository;
import org.opentripplanner.transfer.regular.internal.TransferIndexFactory;

/**
 * Provides a bare {@link TransferRepository} for the graph-build / empty-model path, where no
 * instance is supplied as a Dagger {@code @BindsInstance}.
 * <p>
 * At runtime (graph load) the deserialized {@code TransferRepository} is supplied as a
 * {@code @BindsInstance} on {@code ConstructApplicationFactory} and takes precedence — so this
 * module must NOT be included there (it would create a duplicate binding). It is included only by
 * {@code LoadApplicationFactory}, which produces the empty repo via {@code emptyTransferRepository()}.
 */
@Module
public class EmptyTransferRepositoryModule {

  @Provides
  @Singleton
  public TransferRepository provideEmptyTransferRepository() {
    return new DefaultTransferRepository(TransferIndexFactory.createIndex());
  }
}
