package dev.voldechse.replayframework.storage.s3;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import dev.voldechse.replayframework.storage.ReplayStorage;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.retry.backoff.EqualJitterBackoffStrategy;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/** Internal Guice composition root for the S3 replay storage. */
public final class S3StorageModule extends AbstractModule {

    private final S3StorageConfiguration configuration;
    private final Executor ioExecutor;
    private final Optional<AwsCredentialsProvider> injectedCredentialsProvider;

    public S3StorageModule(
            S3StorageConfiguration configuration,
            Executor ioExecutor) {
        this(configuration, ioExecutor, null);
    }

    public S3StorageModule(
            S3StorageConfiguration configuration,
            Executor ioExecutor,
            AwsCredentialsProvider injectedCredentialsProvider) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.injectedCredentialsProvider = Optional.ofNullable(injectedCredentialsProvider);
    }

    @Provides
    @Singleton
    S3StorageConfiguration provideConfiguration() {
        return configuration;
    }

    @Provides
    @Singleton
    Executor provideIoExecutor() {
        return ioExecutor;
    }

    @Provides
    @Singleton
    AwsCredentialsProvider provideCredentialsProvider() {
        return injectedCredentialsProvider.orElseGet(() -> switch (
                configuration.credentialProviderSelection()) {
            case DEFAULT_CHAIN -> DefaultCredentialsProvider.create();
            case PROFILE -> ProfileCredentialsProvider.create(
                    configuration.profileName().orElseThrow());
        });
    }

    @Provides
    @Singleton
    S3AsyncClient provideClient(AwsCredentialsProvider credentialsProvider) {
        EqualJitterBackoffStrategy backoff = EqualJitterBackoffStrategy.builder()
                .baseDelay(configuration.baseBackoff())
                .maxBackoffTime(configuration.maxBackoff())
                .build();
        RetryPolicy retryPolicy = RetryPolicy.builder()
                .numRetries(configuration.maxAttempts() - 1)
                .backoffStrategy(backoff)
                .throttlingBackoffStrategy(backoff)
                .build();
        ClientOverrideConfiguration override = ClientOverrideConfiguration.builder()
                .retryPolicy(retryPolicy)
                .build();

        S3AsyncClientBuilder builder = S3AsyncClient.builder()
                .region(configuration.region())
                .credentialsProvider(credentialsProvider)
                .forcePathStyle(configuration.pathStyleAccessEnabled())
                .multipartEnabled(true)
                .overrideConfiguration(override);
        configuration.endpointOverride().ifPresent(builder::endpointOverride);
        return builder.build();
    }

    @Provides
    @Singleton
    S3ReplayStorage provideStorage(
            S3AsyncClient client,
            S3StorageConfiguration configuration,
            Executor ioExecutor) {
        return new S3ReplayStorage(client, configuration, ioExecutor);
    }

    @Provides
    @Singleton
    ReplayStorage provideReplayStorage(S3ReplayStorage storage) {
        return storage;
    }
}
