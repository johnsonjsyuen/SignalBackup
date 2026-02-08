package com.johnsonyuen.signalbackup.data.repository;

import com.johnsonyuen.signalbackup.data.remote.GoogleDriveService;
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
public final class DriveRepositoryImpl_Factory implements Factory<DriveRepositoryImpl> {
  private final Provider<GoogleDriveService> driveServiceProvider;

  public DriveRepositoryImpl_Factory(Provider<GoogleDriveService> driveServiceProvider) {
    this.driveServiceProvider = driveServiceProvider;
  }

  @Override
  public DriveRepositoryImpl get() {
    return newInstance(driveServiceProvider.get());
  }

  public static DriveRepositoryImpl_Factory create(
      Provider<GoogleDriveService> driveServiceProvider) {
    return new DriveRepositoryImpl_Factory(driveServiceProvider);
  }

  public static DriveRepositoryImpl newInstance(GoogleDriveService driveService) {
    return new DriveRepositoryImpl(driveService);
  }
}
