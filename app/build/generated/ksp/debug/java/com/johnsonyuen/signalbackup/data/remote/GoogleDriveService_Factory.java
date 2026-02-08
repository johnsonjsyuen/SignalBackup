package com.johnsonyuen.signalbackup.data.remote;

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.johnsonyuen.signalbackup.data.local.datastore.SettingsDataStore;
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

  private final Provider<SettingsDataStore> settingsDataStoreProvider;

  public GoogleDriveService_Factory(Provider<GoogleAccountCredential> credentialProvider,
      Provider<SettingsDataStore> settingsDataStoreProvider) {
    this.credentialProvider = credentialProvider;
    this.settingsDataStoreProvider = settingsDataStoreProvider;
  }

  @Override
  public GoogleDriveService get() {
    return newInstance(credentialProvider.get(), settingsDataStoreProvider.get());
  }

  public static GoogleDriveService_Factory create(
      Provider<GoogleAccountCredential> credentialProvider,
      Provider<SettingsDataStore> settingsDataStoreProvider) {
    return new GoogleDriveService_Factory(credentialProvider, settingsDataStoreProvider);
  }

  public static GoogleDriveService newInstance(GoogleAccountCredential credential,
      SettingsDataStore settingsDataStore) {
    return new GoogleDriveService(credential, settingsDataStore);
  }
}
