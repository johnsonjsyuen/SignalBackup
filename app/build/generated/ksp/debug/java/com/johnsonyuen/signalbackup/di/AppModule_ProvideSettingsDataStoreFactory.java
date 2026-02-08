package com.johnsonyuen.signalbackup.di;

import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;
import com.johnsonyuen.signalbackup.data.local.datastore.SettingsDataStore;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
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
public final class AppModule_ProvideSettingsDataStoreFactory implements Factory<SettingsDataStore> {
  private final Provider<DataStore<Preferences>> dataStoreProvider;

  public AppModule_ProvideSettingsDataStoreFactory(
      Provider<DataStore<Preferences>> dataStoreProvider) {
    this.dataStoreProvider = dataStoreProvider;
  }

  @Override
  public SettingsDataStore get() {
    return provideSettingsDataStore(dataStoreProvider.get());
  }

  public static AppModule_ProvideSettingsDataStoreFactory create(
      Provider<DataStore<Preferences>> dataStoreProvider) {
    return new AppModule_ProvideSettingsDataStoreFactory(dataStoreProvider);
  }

  public static SettingsDataStore provideSettingsDataStore(DataStore<Preferences> dataStore) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideSettingsDataStore(dataStore));
  }
}
