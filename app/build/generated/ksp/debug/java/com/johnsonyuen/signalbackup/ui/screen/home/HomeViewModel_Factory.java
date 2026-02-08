package com.johnsonyuen.signalbackup.ui.screen.home;

import android.content.Context;
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository;
import com.johnsonyuen.signalbackup.domain.usecase.PerformUploadUseCase;
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
  private final Provider<PerformUploadUseCase> performUploadUseCaseProvider;

  private final Provider<SettingsRepository> settingsRepositoryProvider;

  private final Provider<Context> appContextProvider;

  public HomeViewModel_Factory(Provider<PerformUploadUseCase> performUploadUseCaseProvider,
      Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<Context> appContextProvider) {
    this.performUploadUseCaseProvider = performUploadUseCaseProvider;
    this.settingsRepositoryProvider = settingsRepositoryProvider;
    this.appContextProvider = appContextProvider;
  }

  @Override
  public HomeViewModel get() {
    return newInstance(performUploadUseCaseProvider.get(), settingsRepositoryProvider.get(), appContextProvider.get());
  }

  public static HomeViewModel_Factory create(
      Provider<PerformUploadUseCase> performUploadUseCaseProvider,
      Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<Context> appContextProvider) {
    return new HomeViewModel_Factory(performUploadUseCaseProvider, settingsRepositoryProvider, appContextProvider);
  }

  public static HomeViewModel newInstance(PerformUploadUseCase performUploadUseCase,
      SettingsRepository settingsRepository, Context appContext) {
    return new HomeViewModel(performUploadUseCase, settingsRepository, appContext);
  }
}
