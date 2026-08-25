package dev.voldechse.replayframework.storage.s3;

import dev.voldechse.replayframework.storage.ReplayStorage;
import dev.voldechse.replayframework.storage.ReplayStorageContract;
import org.junit.jupiter.api.AfterAll;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

class S3ReplayStorageTest extends ReplayStorageContract {

    private static final List<S3ReplayStorage> backends = new CopyOnWriteArrayList<>();
    private S3ReplayStorage backend;

    @Override
    protected ReplayStorage createStorage(Path root, Executor executor) {
        String endpoint = requiredProperty("replay.s3.test.endpoint");
        String bucket = requiredProperty("replay.s3.test.bucket");
        String region = requiredProperty("replay.s3.test.region");
        String accessKey = requiredProperty("replay.s3.test.access-key");
        String secretKey = requiredProperty("replay.s3.test.secret-key");

        S3StorageConfiguration configuration = S3StorageConfiguration.builder()
                .bucket(bucket)
                .prefix("contract-" + System.nanoTime())
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .pathStyleAccessEnabled(true)
                .maxAttempts(1)
                .baseBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(1))
                .build();

        S3AsyncClient client = S3AsyncClient.builder()
                .region(configuration.region())
                .endpointOverride(configuration.endpointOverride().orElseThrow())
                .forcePathStyle(configuration.pathStyleAccessEnabled())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
        backend = new S3ReplayStorage(client, configuration, executor, root);
        backends.add(backend);
        return backend;
    }

    @AfterAll
    static void closeBackends() throws IOException {
        IOException failure = null;
        for (S3ReplayStorage current : backends) {
            try {
                current.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = new IOException("S3 client close failed", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        backends.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required S3 test property: " + name);
        }
        return value;
    }
}
