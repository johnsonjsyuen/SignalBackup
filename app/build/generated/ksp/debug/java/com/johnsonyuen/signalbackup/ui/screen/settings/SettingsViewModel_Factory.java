package com.johnsonyuen.signalbackup.ui.screen.settings;

import com.johnsonyuen.signalbackup.data.repository.DriveRepository;
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository;
import com.johnsonyuen.signalbackup.domain.usecase.ScheduleUploadUseCase;
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
public final class SettingsViewModel_Factory implements Factory<SettingsViewModel> {
  private final Provider<SettingsRepository> settingsRepositoryProvider;

  private final Provider<DriveRepository> driveRepositoryProvider;

  private final Provider<ScheduleUploadUseCase> scheduleUploadUseCaseProvider;

  public SettingsViewModel_Factory(Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<DriveRepository> driveRepositoryProvider,
      Provider<ScheduleUploadUseCase> scheduleUploadUseCaseProvider) {
    this.settingsRepositoryProvider = settingsRepositoryProvider;
    this.driveRepositoryProvider = driveRepositoryProvider;
    this.scheduleUploadUseCaseProvider = scheduleUploadUseCaseProvider;
  }

  @Override
  public SettingsViewModel get() {
    return newInstance(settingsRepositoryProvider.get(), driveRepositoryProvider.get(), scheduleUploadUseCaseProvider.get());
  }

  public static SettingsViewModel_Factory create(
      Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<DriveRepository> driveRepositoryProvider,
      Provider<ScheduleUploadUseCase> scheduleUploadUseCaseProvider) {
    return new SettingsViewModel_Factory(settingsRepositoryProvider, driveRepositoryProvider, scheduleUploadUseCaseProvider);
  }

  public static SettingsViewModel newInstance(SettingsRepository settingsRepository,
      DriveRepository driveRepository, ScheduleUploadUseCase scheduleUploadUseCase) {
    return new SettingsViewModel(settingsRepository, driveRepository, scheduleUploadUseCase);
  }
}
