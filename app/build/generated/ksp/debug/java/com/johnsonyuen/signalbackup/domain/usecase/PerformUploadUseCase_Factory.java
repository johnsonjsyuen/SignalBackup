package com.johnsonyuen.signalbackup.domain.usecase;

import com.johnsonyuen.signalbackup.data.repository.DriveRepository;
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository;
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
public final class PerformUploadUseCase_Factory implements Factory<PerformUploadUseCase> {
  private final Provider<SettingsRepository> settingsRepositoryProvider;

  private final Provider<DriveRepository> driveRepositoryProvider;

  private final Provider<UploadHistoryRepository> uploadHistoryRepositoryProvider;

  public PerformUploadUseCase_Factory(Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<DriveRepository> driveRepositoryProvider,
      Provider<UploadHistoryRepository> uploadHistoryRepositoryProvider) {
    this.settingsRepositoryProvider = settingsRepositoryProvider;
    this.driveRepositoryProvider = driveRepositoryProvider;
    this.uploadHistoryRepositoryProvider = uploadHistoryRepositoryProvider;
  }

  @Override
  public PerformUploadUseCase get() {
    return newInstance(settingsRepositoryProvider.get(), driveRepositoryProvider.get(), uploadHistoryRepositoryProvider.get());
  }

  public static PerformUploadUseCase_Factory create(
      Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<DriveRepository> driveRepositoryProvider,
      Provider<UploadHistoryRepository> uploadHistoryRepositoryProvider) {
    return new PerformUploadUseCase_Factory(settingsRepositoryProvider, driveRepositoryProvider, uploadHistoryRepositoryProvider);
  }

  public static PerformUploadUseCase newInstance(SettingsRepository settingsRepository,
      DriveRepository driveRepository, UploadHistoryRepository uploadHistoryRepository) {
    return new PerformUploadUseCase(settingsRepository, driveRepository, uploadHistoryRepository);
  }
}
