package com.johnsonyuen.signalbackup.domain.usecase;

import androidx.work.WorkManager;
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository;
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
public final class ScheduleUploadUseCase_Factory implements Factory<ScheduleUploadUseCase> {
  private final Provider<WorkManager> workManagerProvider;

  private final Provider<SettingsRepository> settingsRepositoryProvider;

  public ScheduleUploadUseCase_Factory(Provider<WorkManager> workManagerProvider,
      Provider<SettingsRepository> settingsRepositoryProvider) {
    this.workManagerProvider = workManagerProvider;
    this.settingsRepositoryProvider = settingsRepositoryProvider;
  }

  @Override
  public ScheduleUploadUseCase get() {
    return newInstance(workManagerProvider.get(), settingsRepositoryProvider.get());
  }

  public static ScheduleUploadUseCase_Factory create(Provider<WorkManager> workManagerProvider,
      Provider<SettingsRepository> settingsRepositoryProvider) {
    return new ScheduleUploadUseCase_Factory(workManagerProvider, settingsRepositoryProvider);
  }

  public static ScheduleUploadUseCase newInstance(WorkManager workManager,
      SettingsRepository settingsRepository) {
    return new ScheduleUploadUseCase(workManager, settingsRepository);
  }
}
