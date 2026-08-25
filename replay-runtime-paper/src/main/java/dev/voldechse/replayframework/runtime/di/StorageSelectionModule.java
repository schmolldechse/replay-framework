package dev.voldechse.replayframework.runtime.di;

import com.google.inject.AbstractModule;
import dev.voldechse.replayframework.runtime.config.ReplayRuntimeConfiguration;
import dev.voldechse.replayframework.storage.ReplayStorage;
import dev.voldechse.replayframework.storage.local.LocalReplayStorage;
import dev.voldechse.replayframework.storage.s3.S3StorageConfiguration;
import dev.voldechse.replayframework.storage.s3.S3StorageModule;
import dev.voldechse.replayframework.storage.sftp.SftpStorageConfiguration;
import dev.voldechse.replayframework.storage.sftp.SftpStorageModule;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import software.amazon.awssdk.regions.Region;

/** Installs exactly one configured replay storage backend. */
public final class StorageSelectionModule extends AbstractModule {
    private final ReplayRuntimeConfiguration.StorageSettings settings;
    private final Executor ioExecutor;

    public StorageSelectionModule(
            ReplayRuntimeConfiguration.StorageSettings settings,
            Executor ioExecutor) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    @Override
    protected void configure() {
        switch (settings.backend()) {
            case LOCAL -> bindLocal();
            case S3 -> install(new S3StorageModule(toS3Configuration(), ioExecutor));
            case SFTP -> install(new SftpStorageModule(toSftpConfiguration(), ioExecutor));
        }
    }

    private void bindLocal() {
        LocalReplayStorage storage = new LocalReplayStorage(settings.localRoot(), ioExecutor);
        bind(LocalReplayStorage.class).toInstance(storage);
        bind(ReplayStorage.class).toInstance(storage);
    }

    private S3StorageConfiguration toS3Configuration() {
        ReplayRuntimeConfiguration.S3Settings source = settings.s3().orElseThrow(
                () -> new IllegalArgumentException("S3 configuration is not selected"));
        S3StorageConfiguration.CredentialProviderSelection credentials;
        try {
            credentials = S3StorageConfiguration.CredentialProviderSelection.valueOf(
                    source.credentialProvider());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "storage.s3.credentialProvider must be DEFAULT_CHAIN or PROFILE", failure);
        }
        S3StorageConfiguration.Builder builder = S3StorageConfiguration.builder()
                .bucket(source.bucket())
                .prefix(source.prefix())
                .region(Region.of(source.region()))
                .pathStyleAccessEnabled(source.pathStyleAccessEnabled())
                .credentialProviderSelection(credentials)
                .maxAttempts(source.maxAttempts())
                .baseBackoff(source.baseBackoff())
                .maxBackoff(source.maxBackoff());
        source.endpointOverride().ifPresent(builder::endpointOverride);
        source.profileName().ifPresent(builder::profileName);
        return builder.build();
    }

    private SftpStorageConfiguration toSftpConfiguration() {
        ReplayRuntimeConfiguration.SftpSettings source = settings.sftp().orElseThrow(
                () -> new IllegalArgumentException("SFTP configuration is not selected"));
        SftpStorageConfiguration.AuthenticationMethod authentication;
        try {
            authentication = SftpStorageConfiguration.AuthenticationMethod.valueOf(
                    source.authentication());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "storage.sftp.authentication must be PASSWORD, PRIVATE_KEY or SSH_AGENT",
                    failure);
        }
        SftpStorageConfiguration.Builder builder = SftpStorageConfiguration.builder()
                .host(source.host())
                .port(source.port())
                .username(source.username())
                .basePath(source.basePath())
                .knownHostsFile(source.knownHostsFile().orElseThrow(
                        () -> new IllegalArgumentException(
                                "storage.sftp.knownHostsFile is required")))
                .poolSize(source.poolSize())
                .acquireTimeout(source.acquireTimeout())
                .connectTimeout(source.connectTimeout())
                .operationTimeout(source.operationTimeout())
                .closeTimeout(source.closeTimeout())
                .maxAttempts(source.maxAttempts())
                .baseBackoff(source.baseBackoff())
                .maxBackoff(source.maxBackoff());
        switch (authentication) {
            case PASSWORD -> builder.password(secretSource(
                    source.passwordEnv().orElseThrow(() -> new IllegalArgumentException(
                            "storage.sftp.passwordEnv is required for PASSWORD"))));
            case PRIVATE_KEY -> {
                Path key = source.privateKeyFile().orElseThrow(() -> new IllegalArgumentException(
                        "storage.sftp.privateKeyFile is required for PRIVATE_KEY"));
                Optional<String> passphrase = source.privateKeyPassphraseEnv();
                if (passphrase.isPresent()) {
                    builder.privateKey(key, secretSource(passphrase.orElseThrow()));
                } else {
                    builder.privateKey(key);
                }
            }
            case SSH_AGENT -> builder.sshAgent();
        }
        return builder.build();
    }

    private static SftpStorageConfiguration.SecretSource secretSource(String environmentName) {
        String value = System.getenv(environmentName);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "configured SFTP secret environment variable is missing");
        }
        return () -> value.toCharArray();
    }
}
