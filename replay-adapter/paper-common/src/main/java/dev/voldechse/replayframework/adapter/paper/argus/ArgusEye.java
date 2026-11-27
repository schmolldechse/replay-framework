package dev.voldechse.replayframework.adapter.paper.argus;

/**
 * One virtual observer: a packet recipient that belongs to a recording
 * instead of a player and that no client can perceive.
 *
 * <p>This is the only part of Argus an adapter implements against server
 * internals. All methods are called on the server thread.</p>
 */
public interface ArgusEye extends AutoCloseable {

    /** Advances whatever the server only drives for real players. */
    void tick();

    /** Moves the observation point; the view follows on the next ticks. */
    void moveTo(double x, double y, double z);

    /** Stops observing. Packets caused by the teardown are not delivered. */
    @Override
    void close();
}
