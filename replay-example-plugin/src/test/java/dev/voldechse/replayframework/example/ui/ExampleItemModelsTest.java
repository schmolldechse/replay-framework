package dev.voldechse.replayframework.example.ui;

import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExampleItemModelsTest {

    @Test
    void parsesImmutableBrowserAndHotbarConfiguration() {
        ExampleItemModels models = ExampleItemModels.parse(validConfiguration(), "test-config.json");

        assertEquals(54, models.browser().size());
        assertEquals(45, models.browser().replaySlots().size());
        assertEquals(10, models.hotbar().rewindSeconds());
        assertEquals(
                "replay_example:item/speed_4",
                models.item(ExampleItemModels.HotbarAction.SPEED_CYCLE)
                        .speedModels()
                        .get(PlaybackSpeed.QUADRUPLE)
                        .itemModelKey()
                        .orElseThrow());
        assertEquals(
                Optional.of("replay_example:item/play"),
                models.item(ExampleItemModels.HotbarAction.TOGGLE_PLAY)
                        .alternateModel()
                        .flatMap(asset -> asset.itemModelKey()));
    }

    @Test
    void rejectsUnknownModelsWithConfigurationPath() {
        String invalid = validConfiguration().replace(
                "replay_example:item/leave",
                "replay_example:item/not-present");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ExampleItemModels.parse(invalid, "test-config.json"));

        assertEquals(true, failure.getMessage().contains("test-config.json"));
    }

    private static String validConfiguration() {
        return """
                {
                  "browser": {
                    "title": "Replay Browser",
                    "size": 54,
                    "replaySlots": [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44],
                    "previousPageSlot": 45,
                    "closeSlot": 49,
                    "nextPageSlot": 53,
                    "serverTimeZone": "Europe/Berlin",
                    "loreLineLength": 45
                  },
                  "hotbar": {
                    "rewindSeconds": 10,
                    "forwardSeconds": 10,
                    "items": [
                      {"action":"TOGGLE_PLAY","slot":0,"material":"CLOCK","model":"replay_example:item/pause","alternateModel":"replay_example:item/play"},
                      {"action":"REWIND","slot":1,"material":"CLOCK","model":"replay_example:item/rewind"},
                      {"action":"FORWARD","slot":2,"material":"CLOCK","model":"replay_example:item/forward"},
                      {"action":"RESTART","slot":3,"material":"CLOCK","model":"replay_example:item/restart"},
                      {"action":"SPEED_CYCLE","slot":4,"material":"CLOCK","model":"replay_example:item/speed_1","speedModels":{"0.25":"replay_example:item/speed_0_25","0.5":"replay_example:item/speed_0_5","1":"replay_example:item/speed_1","2":"replay_example:item/speed_2","4":"replay_example:item/speed_4"}},
                      {"action":"LEAVE","slot":8,"material":"BARRIER","model":"replay_example:item/leave"}
                    ]
                  }
                }
                """;
    }
}
