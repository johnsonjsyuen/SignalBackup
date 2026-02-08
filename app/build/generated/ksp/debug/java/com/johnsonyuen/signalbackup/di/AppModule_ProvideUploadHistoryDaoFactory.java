package com.johnsonyuen.signalbackup.di;

import com.johnsonyuen.signalbackup.data.local.db.AppDatabase;
import com.johnsonyuen.signalbackup.data.local.db.UploadHistoryDao;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
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
public final class AppModule_ProvideUploadHistoryDaoFactory implements Factory<UploadHistoryDao> {
  private final Provider<AppDatabase> dbProvider;

  public AppModule_ProvideUploadHistoryDaoFactory(Provider<AppDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public UploadHistoryDao get() {
    return provideUploadHistoryDao(dbProvider.get());
  }

  public static AppModule_ProvideUploadHistoryDaoFactory create(Provider<AppDatabase> dbProvider) {
    return new AppModule_ProvideUploadHistoryDaoFactory(dbProvider);
  }

  public static UploadHistoryDao provideUploadHistoryDao(AppDatabase db) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideUploadHistoryDao(db));
  }
}
