package currentBot.Controllers;

import battlecode.common.RobotType;

public class PlayerConstants {
    // movement solver constants
    static final int GIVE_UP_THRESHOLD = 20; // How many moves before giving up and calling a drone taxi (keep in mind they are in the same soup cluster)
    static public final int MINER_POLLUTION_THRESHOLD = 6000; // Just ignore blocks with this much pollution
    static public final int DRONE_POLLUTION_THRESHOLD = 6000;
    // distance squared constants
    static final int HQ_VISION = 45;
    static final int MINER_VISION = 34;
    static final int ALL_VISION = 20;
    static final int OUTSIDE_NET_GUN_RANGE = 25;
    static final int NET_GUN_RANGE = 13;

    // Miner constants
    static final int REFINERY_BUILD_THRESHOLD = 200 + 150; // (+150 for rush) Soup required before building a refinery
    static final int REFINERY_BUILD_CLUSTER_SIZE = 10; // How many soup squares to justify building a refinery
    static final int RUSH_THRESHOLD = 150; // How much soup before initiating a rush
    static final int AREA_PER_MINER = 2; // how much new soup needs to be found before spawning another miner
    static final int SOUP_PER_MINER = 600;
    static final int MOVES_BY_MINER = 10; // How many moves a miner makes to explore an unexplored tile in its territory (before giving up)
    static final int INSTA_BUILD_MINERS = 4; // How many miners to build as fast as possible
    static final int DISTANCE_FROM_REFINERY = 10;
    static final int START_BUILD_WALL = 299; // turn to start building wall around hq

    static final int RUSH1 = 50;
    static final int RUSH2 = 51;
    static final int RUSH3 = 52;
    static final int SPAM_BUILD_DRONES = 600;
    static final int ELEVATE_BUILD = 900000;// sorry 1300;
    static final int FLIP_TO_LATTICE = 200; // For building


    // Drone constants
    static final boolean ADJACENT_DEFEND = false;
    static final int DEFENSE_RADIUS = 32;
    static final int DEFENSE_CAMP = 15; // radius from hq to defend if NOT adjacent defence, so can still detect enemies near hq
    static final int NET_GUN_RADIUS = 15;
    static final int CAMPING_RADIUS = 25; // for camping just outside net gun range
    static final int SWITCH_TO_ATTACK = 600; // turn for switching to attack mode
    static final int DEFEND_NUMBER = 7;
    static final int TAXI_TIME = 300;
    static final int WAIT_FRIENDS_BEFORE_SUDOKU = 8; // number of friends to wait for within sensor radius before i sudoku
    static final int ENEMY_LANDSCAPER_ALIVE = 0;
    static final int CONTEST_SOUP_CLUSTER_SIZE = 20;

    static final int GRID_BLOCK_SIZE = 7;
    static final public int SEARCH_DIAMETER = 5;


    // Landscaper constants
    static final int DEFEND = 12; // IN CASE DRONES KILL
    static final int HELP = 0; // like for clearing water at soup locations
    static final int ELEVATE_TIME = 800; // 600;
    static final int ELEVATE_ENOUGH = 100; // lasts until round 2500
    static final int ELEVATE_SELF_IF_LONELY = 1000;
    static final int BOTHER_DIGGING = 6; // How much dirt needs to be dug to get to soup

    static int buildSoupRequirements(RobotType buildType) {
        switch (buildType) {
            case DESIGN_SCHOOL:
                return RobotType.DESIGN_SCHOOL.cost;
            case VAPORATOR:
                return RobotType.VAPORATOR.cost;
            case NET_GUN:
                return RobotType.NET_GUN.cost + 100;
            case FULFILLMENT_CENTER:
                return RobotType.FULFILLMENT_CENTER.cost;
            case MINER:
                // extra soup, to allow miner to build a fulfillment center right after spawning
                return RobotType.MINER.cost;
            case DELIVERY_DRONE:
                return RobotType.DELIVERY_DRONE.cost;
            case LANDSCAPER:
                return 50 + RobotType.LANDSCAPER.cost; // want more drones
            default:
                return 0; // Shouldn't get here anyway
        }
    }

    static int buildSoupRequirements(RobotType buildType, boolean usedDrone) {
        switch (buildType) {
            case DESIGN_SCHOOL:
            case FULFILLMENT_CENTER:
                return 500;
            default:
                return PlayerConstants.buildSoupRequirements(buildType);
        }
    }


    static int minerSoupRequirements(int builtMiners, int round) {
        if (builtMiners < PlayerConstants.INSTA_BUILD_MINERS) {
            return RobotType.MINER.cost;
        } else if (round < 400) {
            return 290; // Fulfillment center + 2 miners
        } else {
            return 600; // Allow for building vaporators
        }
    }

    static boolean shouldntDuplicate(RobotType buildType) {
        return (buildType == RobotType.DESIGN_SCHOOL) ||
                (buildType == RobotType.REFINERY) ||
                (buildType == RobotType.FULFILLMENT_CENTER) ||
                (buildType == RobotType.NET_GUN);
    }
}
