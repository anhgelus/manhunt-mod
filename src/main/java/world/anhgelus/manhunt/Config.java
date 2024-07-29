package world.anhgelus.manhunt;

import eu.midnightdust.lib.config.MidnightConfig;

public class Config extends MidnightConfig {
    @Entry(category = "timings") public static int secondsBeforeRelease = 30;
    @Entry(category = "timings") public static int updateCompassEach = 15;
}
