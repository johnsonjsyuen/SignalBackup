package com.johnsonyuen.signalbackup.ui.screen.history;

import com.johnsonyuen.signalbackup.data.repository.UploadHistoryRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation",
    "nullness:initialization.field.uninitialized"
})
public final class HistoryViewModel_Factory implements Factory<HistoryViewModel> {
  private final Provider<UploadHistoryRepository> uploadHistoryRepositoryProvider;

  public HistoryViewModel_Factory(
      Provider<UploadHistoryRepository> uploadHistoryRepositoryProvider) {
    this.uploadHistoryRepositoryProvider = uploadHistoryRepositoryProvider;
  }

  @Override
  public HistoryViewModel get() {
    return newInstance(uploadHistoryRepositoryProvider.get());
  }

  public static HistoryViewModel_Factory create(
      Provider<UploadHistoryRepository> uploadHistoryRepositoryProvider) {
    return new HistoryViewModel_Factory(uploadHistoryRepositoryProvider);
  }

  public static HistoryViewModel newInstance(UploadHistoryRepository uploadHistoryRepository) {
    return new HistoryViewModel(uploadHistoryRepository);
  }
}
