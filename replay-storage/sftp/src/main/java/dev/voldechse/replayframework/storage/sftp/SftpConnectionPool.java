package dev.voldechse.replayframework.storage.sftp;

import com.hierynomus.sshj.key.KeyAlgorithm;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Buffer.PlainBuffer;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.common.Message;
import net.schmizz.sshj.common.SSHPacket;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.method.AbstractAuthMethod;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.net.UnixDomainSocketAddress;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded SSHJ connection/session resource manager for the SFTP backend. */
final class SftpConnectionPool {

    private final SftpStorageConfiguration configuration;
    private final Executor ioExecutor;
    private final ScheduledExecutorService retryScheduler;
    private final Semaphore permits;
    private final Set<ConnectionLease> leases = ConcurrentHashMap.newKeySet();
    private final Object lifecycleMonitor = new Object();
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
    private final AtomicBoolean closeTimeoutScheduled = new AtomicBoolean();
    private PoolState state = PoolState.OPEN;

    SftpConnectionPool(
            SftpStorageConfiguration configuration,
            Executor ioExecutor) {
        this(
                configuration,
                ioExecutor,
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofVirtual().name("replay-sftp-retry-", 0).factory()));
    }

    SftpConnectionPool(
            SftpStorageConfiguration configuration,
            Executor ioExecutor,
            ScheduledExecutorService retryScheduler) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.retryScheduler = Objects.requireNonNull(retryScheduler, "retryScheduler");
        this.permits = new Semaphore(configuration.poolSize(), true);
    }

    CompletionStage<ConnectionLease> acquire() {
        synchronized (lifecycleMonitor) {
            if (state != PoolState.OPEN) {
                return failedStage(new IllegalStateException("SFTP connection pool is closed"));
            }
        }
        return CompletableFuture.supplyAsync(this::openLease, ioExecutor);
    }

    <T> CompletionStage<T> execute(SftpOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        CompletableFuture<T> result = new CompletableFuture<>();
        runAttempt(operation, result, 1);
        return result;
    }

    void release(ConnectionLease lease) {
        if (!lease.released.compareAndSet(false, true)) {
            return;
        }
        try {
            lease.sftp.close();
        } catch (IOException ignored) {
            // A failed close cannot make a released lease usable again.
        }
        try {
            lease.client.close();
        } catch (IOException ignored) {
            // The connection is discarded regardless of close diagnostics.
        }
        leases.remove(lease);
        permits.release();
        finishCloseIfReady();
    }

    CompletionStage<Void> close() {
        synchronized (lifecycleMonitor) {
            if (state == PoolState.OPEN) {
                state = PoolState.CLOSING;
            }
        }
        finishCloseIfReady();
        scheduleCloseTimeout();
        return closeFuture;
    }

    private ConnectionLease openLease() {
        boolean acquired = false;
        SSHClient client = new SSHClient();
        try {
            acquired = permits.tryAcquire(
                    configuration.acquireTimeout().toNanos(), TimeUnit.NANOSECONDS);
            if (!acquired) {
                throw new IOException("SFTP connection pool acquire timed out");
            }
            int connectTimeout = timeoutMillis(configuration.connectTimeout());
            client.setConnectTimeout(connectTimeout);
            client.setTimeout(timeoutMillis(configuration.operationTimeout()));
            client.loadKnownHosts(configuration.knownHostsFile().toFile());
            client.connect(configuration.host(), configuration.port());
            authenticate(client);
            SFTPClient sftp = client.newSFTPClient();
            ConnectionLease lease = new ConnectionLease(client, sftp);
            synchronized (lifecycleMonitor) {
                if (state != PoolState.OPEN) {
                    lease.closeResources();
                    permits.release();
                    acquired = false;
                    throw new IllegalStateException("SFTP connection pool is closing");
                }
                leases.add(lease);
            }
            return lease;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CompletionException(new IOException(
                    "interrupted while acquiring an SFTP connection", exception));
        } catch (Throwable failure) {
            if (acquired) {
                permits.release();
            }
            try {
                client.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new CompletionException(failure);
        }
    }

    private void authenticate(SSHClient client) throws IOException {
        switch (configuration.authenticationMethod()) {
            case PASSWORD -> {
                char[] password = configuration.secretSource()
                        .orElseThrow(() -> new IllegalStateException("password secret is missing"))
                        .read();
                Objects.requireNonNull(password, "password secret");
                try {
                    client.authPassword(configuration.username(), password);
                } finally {
                    java.util.Arrays.fill(password, '\0');
                }
            }
            case PRIVATE_KEY -> {
                KeyProvider keyProvider;
                if (configuration.privateKeyPassphrase().isPresent()) {
                    char[] passphrase = configuration.privateKeyPassphrase().orElseThrow().read();
                    Objects.requireNonNull(passphrase, "private key passphrase");
                    try {
                        keyProvider = client.loadKeys(
                                configuration.privateKeyFile().orElseThrow().toString(), passphrase);
                    } finally {
                        java.util.Arrays.fill(passphrase, '\0');
                    }
                } else {
                    keyProvider = client.loadKeys(
                            configuration.privateKeyFile().orElseThrow().toString());
                }
                client.authPublickey(configuration.username(), keyProvider);
            }
            case SSH_AGENT -> client.auth(
                    configuration.username(),
                    new SshAgentAuthMethod(loadAgentIdentities()));
        }
    }

    private static List<AgentIdentity> loadAgentIdentities() throws IOException {
        String socketPath = System.getenv("SSH_AUTH_SOCK");
        if (socketPath == null || socketPath.isBlank()) {
            throw new IOException("SSH agent authentication requires SSH_AUTH_SOCK");
        }

        byte[] response = agentRequest(Path.of(socketPath), new byte[] {11});
        SSHPacket packet = new SSHPacket(response);
        int responseType = packet.readByte() & 0xff;
        if (responseType != 12) {
            throw new IOException("SSH agent did not return its identities");
        }
        int identityCount = packet.readUInt32AsInt();
        if (identityCount < 0 || identityCount > 1024) {
            throw new IOException("SSH agent returned an invalid identity count");
        }

        List<AgentIdentity> identities = new ArrayList<>(identityCount);
        for (int index = 0; index < identityCount; index++) {
            byte[] keyBlob = packet.readStringAsBytes();
            packet.readStringAsBytes();
            try {
                PublicKey publicKey = new SSHPacket(keyBlob).readPublicKey();
                identities.add(new AgentIdentity(keyBlob, publicKey));
            } catch (IOException | RuntimeException ignored) {
                // An unsupported agent key is skipped so another usable identity can be tried.
            }
        }
        if (identities.isEmpty()) {
            throw new IOException("SSH agent has no SSHJ-compatible identities");
        }
        return identities;
    }

    private static byte[] requestAgentSignature(
            Path socketPath,
            AgentIdentity identity,
            byte[] data,
            String keyAlgorithm) throws IOException {
        int flags = switch (keyAlgorithm) {
            case "rsa-sha2-256" -> 2;
            case "rsa-sha2-512" -> 4;
            default -> 0;
        };
        PlainBuffer request = new PlainBuffer()
                .putByte((byte) 13)
                .putString(identity.keyBlob)
                .putString(data)
                .putUInt32FromInt(flags);
        byte[] response = agentRequest(socketPath, request.getCompactData());
        SSHPacket packet = new SSHPacket(response);
        int responseType = packet.readByte() & 0xff;
        if (responseType != 14) {
            throw new IOException("SSH agent rejected the signature request");
        }
        return packet.readStringAsBytes();
    }

    private static byte[] agentRequest(Path socketPath, byte[] request) throws IOException {
        if (request.length > 1024 * 1024) {
            throw new IOException("SSH agent request is too large");
        }
        try (SocketChannel channel = SocketChannel.open(
                UnixDomainSocketAddress.of(socketPath))) {
            ByteBuffer requestLength = ByteBuffer.allocate(Integer.BYTES)
                    .putInt(request.length);
            requestLength.flip();
            writeFully(channel, requestLength);
            writeFully(channel, ByteBuffer.wrap(request));

            ByteBuffer responseLength = ByteBuffer.allocate(Integer.BYTES);
            readFully(channel, responseLength);
            responseLength.flip();
            int length = responseLength.getInt();
            if (length < 1 || length > 1024 * 1024) {
                throw new IOException("SSH agent returned an invalid response size");
            }
            ByteBuffer response = ByteBuffer.allocate(length);
            readFully(channel, response);
            return response.array();
        }
    }

    private static void writeFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void readFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new EOFException("SSH agent closed its socket early");
            }
        }
    }

    private static final class SshAgentAuthMethod extends AbstractAuthMethod {
        private final List<AgentIdentity> identities;
        private int identityIndex;
        private AgentIdentity identity;
        private String keyAlgorithm;
        private Path socketPath;

        private SshAgentAuthMethod(List<AgentIdentity> identities) {
            super("publickey");
            this.identities = List.copyOf(identities);
        }

        @Override
        public void request() throws UserAuthException, TransportException {
            identity = identities.get(identityIndex);
            try {
                socketPath = Path.of(Objects.requireNonNull(
                        System.getenv("SSH_AUTH_SOCK"), "SSH_AUTH_SOCK"));
                List<KeyAlgorithm> algorithms = params.getTransport()
                        .getClientKeyAlgorithms(KeyType.fromKey(identity.publicKey));
                if (algorithms.isEmpty()) {
                    throw new IOException("SSHJ has no algorithm for an SSH agent identity");
                }
                keyAlgorithm = algorithms.get(0).getKeyAlgorithm();
                params.getTransport().write(buildRequest(false));
            } catch (IOException | RuntimeException exception) {
                throw new UserAuthException("SSH agent request could not be prepared", exception);
            }
        }

        @Override
        public void handle(Message message, SSHPacket packet)
                throws UserAuthException, TransportException {
            if (message != Message.USERAUTH_60) {
                super.handle(message, packet);
                return;
            }
            try {
                SSHPacket request = buildRequest(true);
                byte[] signedPayload = new PlainBuffer()
                        .putString(params.getTransport().getSessionID())
                        .putBuffer(request)
                        .getCompactData();
                byte[] signatureBlob = requestAgentSignature(
                        socketPath,
                        identity,
                        signedPayload,
                        keyAlgorithm);
                SSHPacket signature = new SSHPacket(signatureBlob);
                request.putSignature(
                        signature.readString(),
                        signature.readStringAsBytes());
                params.getTransport().write(request);
            } catch (IOException | RuntimeException exception) {
                throw new UserAuthException("SSH agent signature could not be created", exception);
            }
        }

        @Override
        public boolean shouldRetry() {
            if (identityIndex + 1 >= identities.size()) {
                return false;
            }
            identityIndex++;
            return true;
        }

        private SSHPacket buildRequest(boolean signed) throws UserAuthException {
            return super.buildReq()
                    .putBoolean(signed)
                    .putString(keyAlgorithm)
                    .putString(identity.keyBlob);
        }
    }

    private static final class AgentIdentity {
        private final byte[] keyBlob;
        private final PublicKey publicKey;

        private AgentIdentity(byte[] keyBlob, PublicKey publicKey) {
            this.keyBlob = keyBlob.clone();
            this.publicKey = publicKey;
        }
    }

    private <T> void runAttempt(
            SftpOperation<T> operation,
            CompletableFuture<T> result,
            int attempt) {
        acquire().whenComplete((lease, acquireFailure) -> {
            if (acquireFailure != null) {
                retryOrFail(operation, result, attempt, unwrap(acquireFailure));
                return;
            }
            CompletableFuture.supplyAsync(() -> {
                try {
                    return operation.apply(lease);
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                } finally {
                    release(lease);
                }
            }, ioExecutor).whenComplete((value, failure) -> {
                if (failure == null) {
                    result.complete(value);
                } else {
                    retryOrFail(operation, result, attempt, unwrap(failure));
                }
            });
        });
    }

    private <T> void retryOrFail(
            SftpOperation<T> operation,
            CompletableFuture<T> result,
            int attempt,
            Throwable failure) {
        if (!isRetryable(failure) || attempt >= configuration.maxAttempts()) {
            result.completeExceptionally(failure);
            return;
        }
        long delay = backoffMillis(attempt);
        try {
            retryScheduler.schedule(
                    () -> runAttempt(operation, result, attempt + 1),
                    delay,
                    TimeUnit.MILLISECONDS);
        } catch (RuntimeException schedulingFailure) {
            result.completeExceptionally(schedulingFailure);
        }
    }

    private void finishCloseIfReady() {
        boolean complete;
        synchronized (lifecycleMonitor) {
            complete = state == PoolState.CLOSING && leases.isEmpty();
            if (complete) {
                state = PoolState.CLOSED;
            }
        }
        if (complete) {
            retryScheduler.shutdownNow();
            closeFuture.complete(null);
        }
    }

    private void scheduleCloseTimeout() {
        if (closeFuture.isDone() || !closeTimeoutScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            retryScheduler.schedule(() -> {
                boolean timedOut;
                synchronized (lifecycleMonitor) {
                    timedOut = state == PoolState.CLOSING && !leases.isEmpty();
                    if (timedOut) {
                        state = PoolState.CLOSED;
                    }
                }
                if (timedOut) {
                    retryScheduler.shutdownNow();
                    closeFuture.completeExceptionally(new TimeoutException(
                            "SFTP connection pool close timed out"));
                }
            }, configuration.closeTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException schedulingFailure) {
            closeFuture.completeExceptionally(schedulingFailure);
        }
    }

    private long backoffMillis(int attempt) {
        long base = Math.max(1L, configuration.baseBackoff().toMillis());
        long maximum = Math.max(base, configuration.maxBackoff().toMillis());
        long multiplier = 1L << Math.min(attempt - 1, 20);
        return base > maximum / multiplier ? maximum : base * multiplier;
    }

    private static boolean isRetryable(Throwable failure) {
        return failure instanceof SocketTimeoutException
                || failure instanceof ConnectException
                || failure instanceof SocketException
                || failure instanceof EOFException;
    }

    private static int timeoutMillis(Duration duration) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, duration.toMillis()));
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> CompletionStage<T> failedStage(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(failure);
        return result;
    }

    @FunctionalInterface
    interface SftpOperation<T> {
        T apply(ConnectionLease lease) throws Exception;
    }

    private enum PoolState {
        OPEN,
        CLOSING,
        CLOSED
    }

    static final class ConnectionLease {
        private final SSHClient client;
        private final SFTPClient sftp;
        private final AtomicBoolean released = new AtomicBoolean();

        private ConnectionLease(SSHClient client, SFTPClient sftp) {
            this.client = client;
            this.sftp = sftp;
        }

        SFTPClient sftp() {
            return sftp;
        }

        private void closeResources() {
            try {
                sftp.close();
            } catch (IOException ignored) {
                // The lease was never published to the pool.
            }
            try {
                client.close();
            } catch (IOException ignored) {
                // The lease was never published to the pool.
            }
        }
    }
}
