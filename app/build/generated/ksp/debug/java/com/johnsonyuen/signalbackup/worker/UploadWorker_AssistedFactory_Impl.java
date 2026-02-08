package com.johnsonyuen.signalbackup.worker;

import android.content.Context;
import androidx.work.WorkerParameters;
import dagger.internal.DaggerGenerated;
import dagger.internal.InstanceFactory;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class UploadWorker_AssistedFactory_Impl implements UploadWorker_AssistedFactory {
  private final UploadWorker_Factory delegateFactory;

  UploadWorker_AssistedFactory_Impl(UploadWorker_Factory delegateFactory) {
    this.delegateFactory = delegateFactory;
  }

  @Override
  public UploadWorker create(Context p0, WorkerParameters p1) {
    return delegateFactory.get(p0, p1);
  }

  public static Provider<UploadWorker_AssistedFactory> create(
      UploadWorker_Factory delegateFactory) {
    return InstanceFactory.create(new UploadWorker_AssistedFactory_Impl(delegateFactory));
  }

  public static dagger.internal.Provider<UploadWorker_AssistedFactory> createFactoryProvider(
      UploadWorker_Factory delegateFactory) {
    return InstanceFactory.create(new UploadWorker_AssistedFactory_Impl(delegateFactory));
  }
}
