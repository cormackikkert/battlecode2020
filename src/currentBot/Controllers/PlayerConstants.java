package currentBot.Controllers;

import battlecode.common.RobotType;

public class PlayerConstants {
    // Miner constants
    static final int REFINERY_BUILD_THRESHOLD = 200 + 150; // (+150 for rush) Soup required before building a refinery
    static final int REFINERY_BUILD_CLUSTER_SIZE = 10; // How many soup squares to justify building a refinery
    static final int RUSH_THRESHOLD = 150; // How much soup before initiating a rush
    static final int SOUP_PER_MINER = 8; // how much new soup needs to be found before spawning another miner
    static final int MOVES_BY_MINER = 10; // How many moves a miner makes to explore an unexplored tile in its territory (before giving up)

    // Drone constants
    static final boolean ADJACENT_DEFEND = true;
    static final int DEFENSE_RADIUS = 35; // radius from hq to defend if NOT adjacent defence
    static final int NET_GUN_RADIUS = 15;
    static final int CAMPING_RADIUS = 25; // for camping just outside net gun range
    static final int SWITCH_TO_ATTACK = 600; // turn for switching to attack mode
    static final int DEFEND_NUMBER = 7;

    static int buildSoupRequirements(RobotType buildType) {
        switch (buildType) {
            case DESIGN_SCHOOL:
                return RobotType.DESIGN_SCHOOL.cost;
            case VAPORATOR:
                return RobotType.VAPORATOR.cost;
            case NET_GUN:
                return RobotType.NET_GUN.cost;
            case FULFILLMENT_CENTER:
                return RobotType.FULFILLMENT_CENTER.cost;
            default:
                return 0; // Shouldn't get here anyway
        }
    }
}
