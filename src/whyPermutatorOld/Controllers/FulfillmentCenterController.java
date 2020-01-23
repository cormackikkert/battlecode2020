package whyPermutatorOld.Controllers;

import battlecode.common.*;

public class FulfillmentCenterController extends Controller {

    static final int PRODUCTION_CAP = Integer.MAX_VALUE; // spam drones

    // favourable spawn directions
    static Direction[] n = {Direction.NORTH, Direction.NORTHWEST, Direction.NORTHEAST};
    static Direction[] e = {Direction.EAST, Direction.NORTHEAST, Direction.SOUTHEAST};
    static Direction[] s = {Direction.SOUTH, Direction.SOUTHEAST, Direction.SOUTHWEST};
    static Direction[] w = {Direction.WEST, Direction.SOUTHWEST, Direction.NORTHWEST};

    static Direction[] ne = {Direction.NORTHEAST, Direction.NORTH, Direction.EAST};
    static Direction[] se = {Direction.SOUTHEAST, Direction.EAST, Direction.SOUTH};
    static Direction[] sw = {Direction.SOUTHWEST, Direction.SOUTH, Direction.WEST};
    static Direction[] nw = {Direction.NORTHWEST, Direction.WEST, Direction.NORTH};

    int sent = 0;
    int counter = 0;
    Direction latestDirSpawn;

    int ex = 0;

    public FulfillmentCenterController(RobotController rc) {
        getInfo(rc);
        // Probably built one before
        if (rc.getRoundNum() > 500) ex = 400;
    }

    public void run() throws GameActionException {
        if (sent >= PRODUCTION_CAP) return;

        // changes cost so other units can be built
        // (round % 10 == 0 is the real bottleneck, not the cost)
        if (rc.getRoundNum() > 800 &&
                GameConstants.getWaterLevel(rc.getRoundNum() + 200) > rc.senseElevation(rc.getLocation()) &&
                rc.getTeamSoup() > Math.min(ex, 400) + PlayerConstants.buildSoupRequirements(RobotType.DELIVERY_DRONE)) {

            buildDrone();
        }

        if (rc.getRoundNum() > 1700 &&
                rc.getTeamSoup() > Math.min(ex, 400) + PlayerConstants.buildSoupRequirements(RobotType.DELIVERY_DRONE)) { // just spam at this point, no need to conserve soup
            buildDrone();
        }

        if (rc.getRoundNum() % 10 == 0 && rc.getTeamSoup() > Math.min(ex, 400) + PlayerConstants.buildSoupRequirements(RobotType.DELIVERY_DRONE)) {
            buildDrone();
            ex += 100;
        }
    }

    public void buildDrone() throws GameActionException {
        boolean netGunNearby = false;
        for (RobotInfo enemy : rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rc.getTeam().opponent())) {
            if (enemy.type == RobotType.NET_GUN) netGunNearby = true;
        }

        if (netGunNearby) {
            for (Direction dir : getDirections()) {
                boolean isSafe = true;
                for (RobotInfo enemy : rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rc.getTeam().opponent())) {
                    if (enemy.type == RobotType.NET_GUN) {
                        if (getDistanceSquared(rc.getLocation().add(dir), enemy.location) <= 24) {
                            isSafe = false;
                        }
                    }
                }
                if (isSafe && rc.canBuildRobot(RobotType.DELIVERY_DRONE, dir)) {
                    rc.buildRobot(RobotType.DELIVERY_DRONE, dir);
                    return;
                }
            }
        } else {
            Direction[] out;
            if (enemyHQ != null && allyHQ != null) {
                if (enemyHQ.x == allyHQ.x) {
                    out = allyHQ.x < mapX / 2 ? e : w;
                } else if (enemyHQ.y == allyHQ.y) {
                    out = allyHQ.y < mapY / 2 ? n : s;
                } else {
                    if (allyHQ.x < mapX / 2) {
                        out = allyHQ.y < mapY / 2 ? ne : se;
                    } else {
                        out = allyHQ.y < mapY / 2 ? nw : sw;
                    }
                }
            } else {
                if (rc.getLocation().x < mapX / 2) {
                    out = rc.getLocation().y < mapY / 2 ? ne : se;
                } else {
                    out = rc.getLocation().y < mapY / 2 ? nw : sw;
                }
            }
            if (tryBuild(RobotType.DELIVERY_DRONE, out[counter])) {
                counter = (counter + 1) % 3;
            }
            for (Direction direction : directions) {
                tryBuild(RobotType.DELIVERY_DRONE, direction);
            }
        }
    }
}