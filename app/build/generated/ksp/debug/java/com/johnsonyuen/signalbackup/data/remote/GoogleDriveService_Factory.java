package com.johnsonyuen.signalbackup.data.remote;

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
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
public final class GoogleDriveService_Factory implements Factory<GoogleDriveService> {
  private final Provider<GoogleAccountCredential> credentialProvider;

  public GoogleDriveService_Factory(Provider<GoogleAccountCredential> credentialProvider) {
    this.credentialProvider = credentialProvider;
  }

  @Override
  public GoogleDriveService get() {
    return newInstance(credentialProvider.get());
  }

  public static GoogleDriveService_Factory create(
      Provider<GoogleAccountCredential> credentialProvider) {
    return new GoogleDriveService_Factory(credentialProvider);
  }

  public static GoogleDriveService newInstance(GoogleAccountCredential credential) {
    return new GoogleDriveService(credential);
  }
}
