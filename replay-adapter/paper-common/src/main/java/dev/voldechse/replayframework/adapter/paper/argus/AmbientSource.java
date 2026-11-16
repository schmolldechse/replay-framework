package dev.voldechse.replayframework.adapter.paper.argus;

import java.util.List;

/**
 * World state the server only announces to listed players, read directly
 * from the world instead: time and weather.
 */
public interface AmbientSource {

    /**
     * Returns the native packets describing what changed since the previous
     * call. Called once per server tick on the server thread.
     */
    List<Object> sample();
}
