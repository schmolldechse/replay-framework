package dev.voldechse.replayframework.core.playback;

import java.util.List;
import java.util.concurrent.CompletionStage;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Paper runtime port for validating and asynchronously placing one observer. */
public interface ObserverPlacementService {
    CompletionStage<Void> place(Player viewer, List<Location> candidates);
}
