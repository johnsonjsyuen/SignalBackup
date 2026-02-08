package com.johnsonyuen.signalbackup.di;

import android.content.Context;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
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
public final class DriveModule_ProvideGoogleAccountCredentialFactory implements Factory<GoogleAccountCredential> {
  private final Provider<Context> contextProvider;

  public DriveModule_ProvideGoogleAccountCredentialFactory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public GoogleAccountCredential get() {
    return provideGoogleAccountCredential(contextProvider.get());
  }

  public static DriveModule_ProvideGoogleAccountCredentialFactory create(
      Provider<Context> contextProvider) {
    return new DriveModule_ProvideGoogleAccountCredentialFactory(contextProvider);
  }

  public static GoogleAccountCredential provideGoogleAccountCredential(Context context) {
    return Preconditions.checkNotNullFromProvides(DriveModule.INSTANCE.provideGoogleAccountCredential(context));
  }
}
