package com.johnsonyuen.signalbackup.ui.screen.home;

import android.content.Context;
import androidx.work.WorkManager;
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository;
import com.johnsonyuen.signalbackup.domain.usecase.ManualUploadUseCase;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class HomeViewModel_Factory implements Factory<HomeViewModel> {
  private final Provider<ManualUploadUseCase> manualUploadUseCaseProvider;

  private final Provider<SettingsRepository> settingsRepositoryProvider;

  private final Provider<WorkManager> workManagerProvider;

  private final Provider<Context> appContextProvider;

  public HomeViewModel_Factory(Provider<ManualUploadUseCase> manualUploadUseCaseProvider,
      Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<WorkManager> workManagerProvider, Provider<Context> appContextProvider) {
    this.manualUploadUseCaseProvider = manualUploadUseCaseProvider;
    this.settingsRepositoryProvider = settingsRepositoryProvider;
    this.workManagerProvider = workManagerProvider;
    this.appContextProvider = appContextProvider;
  }

  @Override
  public HomeViewModel get() {
    return newInstance(manualUploadUseCaseProvider.get(), settingsRepositoryProvider.get(), workManagerProvider.get(), appContextProvider.get());
  }

  public static HomeViewModel_Factory create(
      Provider<ManualUploadUseCase> manualUploadUseCaseProvider,
      Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<WorkManager> workManagerProvider, Provider<Context> appContextProvider) {
    return new HomeViewModel_Factory(manualUploadUseCaseProvider, settingsRepositoryProvider, workManagerProvider, appContextProvider);
  }

  public static HomeViewModel newInstance(ManualUploadUseCase manualUploadUseCase,
      SettingsRepository settingsRepository, WorkManager workManager, Context appContext) {
    return new HomeViewModel(manualUploadUseCase, settingsRepository, workManager, appContext);
  }
}
