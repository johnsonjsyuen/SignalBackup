package com.johnsonyuen.signalbackup;

import androidx.hilt.work.HiltWorkerFactory;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class SignalBackupApp_MembersInjector implements MembersInjector<SignalBackupApp> {
  private final Provider<HiltWorkerFactory> workerFactoryProvider;

  public SignalBackupApp_MembersInjector(Provider<HiltWorkerFactory> workerFactoryProvider) {
    this.workerFactoryProvider = workerFactoryProvider;
  }

  public static MembersInjector<SignalBackupApp> create(
      Provider<HiltWorkerFactory> workerFactoryProvider) {
    return new SignalBackupApp_MembersInjector(workerFactoryProvider);
  }

  @Override
  public void injectMembers(SignalBackupApp instance) {
    injectWorkerFactory(instance, workerFactoryProvider.get());
  }

  @InjectedFieldSignature("com.johnsonyuen.signalbackup.SignalBackupApp.workerFactory")
  public static void injectWorkerFactory(SignalBackupApp instance,
      HiltWorkerFactory workerFactory) {
    instance.workerFactory = workerFactory;
  }
}
