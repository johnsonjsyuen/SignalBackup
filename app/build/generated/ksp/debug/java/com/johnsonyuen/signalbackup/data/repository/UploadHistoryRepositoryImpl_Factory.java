package com.johnsonyuen.signalbackup.data.repository;

import com.johnsonyuen.signalbackup.data.local.db.UploadHistoryDao;
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
public final class UploadHistoryRepositoryImpl_Factory implements Factory<UploadHistoryRepositoryImpl> {
  private final Provider<UploadHistoryDao> daoProvider;

  public UploadHistoryRepositoryImpl_Factory(Provider<UploadHistoryDao> daoProvider) {
    this.daoProvider = daoProvider;
  }

  @Override
  public UploadHistoryRepositoryImpl get() {
    return newInstance(daoProvider.get());
  }

  public static UploadHistoryRepositoryImpl_Factory create(Provider<UploadHistoryDao> daoProvider) {
    return new UploadHistoryRepositoryImpl_Factory(daoProvider);
  }

  public static UploadHistoryRepositoryImpl newInstance(UploadHistoryDao dao) {
    return new UploadHistoryRepositoryImpl(dao);
  }
}
