package com.johnsonyuen.signalbackup.di;

import android.content.Context;
import com.google.android.gms.auth.api.identity.AuthorizationClient;
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
public final class DriveModule_ProvideAuthorizationClientFactory implements Factory<AuthorizationClient> {
  private final Provider<Context> contextProvider;

  public DriveModule_ProvideAuthorizationClientFactory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public AuthorizationClient get() {
    return provideAuthorizationClient(contextProvider.get());
  }

  public static DriveModule_ProvideAuthorizationClientFactory create(
      Provider<Context> contextProvider) {
    return new DriveModule_ProvideAuthorizationClientFactory(contextProvider);
  }

  public static AuthorizationClient provideAuthorizationClient(Context context) {
    return Preconditions.checkNotNullFromProvides(DriveModule.INSTANCE.provideAuthorizationClient(context));
  }
}
