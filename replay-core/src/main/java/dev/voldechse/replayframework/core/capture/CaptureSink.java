package dev.voldechse.replayframework.core.capture;

import dev.voldechse.replayframework.api.id.RecordingSessionId;

/**
 * Non-blocking destination for one recording session in the central capture route.
 *
 * <p>Implementations are called from a Netty event loop and must perform only
 * bounded in-memory work. Queue reservation and overflow reporting remain the
 * responsibility of the recording session.</p>
 */
public interface CaptureSink {

    /** Returns the unique recording session represented by this sink. */
    RecordingSessionId sessionId();

    /** Returns whether this session accepts the packet's scope and policy. */
    boolean accepts(CapturedPacket packet);

    /** Enqueues an accepted packet without blocking the caller. */
    void enqueue(CapturedPacket packet);
}
