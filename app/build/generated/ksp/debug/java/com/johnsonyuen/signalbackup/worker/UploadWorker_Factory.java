package com.johnsonyuen.signalbackup.worker;

import android.content.Context;
import androidx.work.WorkerParameters;
import com.johnsonyuen.signalbackup.domain.usecase.PerformUploadUseCase;
import dagger.internal.DaggerGenerated;
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
public final class UploadWorker_Factory {
  private final Provider<PerformUploadUseCase> performUploadUseCaseProvider;

  public UploadWorker_Factory(Provider<PerformUploadUseCase> performUploadUseCaseProvider) {
    this.performUploadUseCaseProvider = performUploadUseCaseProvider;
  }

  public UploadWorker get(Context appContext, WorkerParameters workerParams) {
    return newInstance(appContext, workerParams, performUploadUseCaseProvider.get());
  }

  public static UploadWorker_Factory create(
      Provider<PerformUploadUseCase> performUploadUseCaseProvider) {
    return new UploadWorker_Factory(performUploadUseCaseProvider);
  }

  public static UploadWorker newInstance(Context appContext, WorkerParameters workerParams,
      PerformUploadUseCase performUploadUseCase) {
    return new UploadWorker(appContext, workerParams, performUploadUseCase);
  }
}
