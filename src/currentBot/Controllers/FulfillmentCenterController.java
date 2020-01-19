package currentBot.Controllers;

import battlecode.common.*;
import currentBot.CommunicationHandler;

import java.util.Arrays;
import java.util.List;

public class FulfillmentCenterController extends Controller {

    static final int PRODUCTION_CAP = Integer.MAX_VALUE; // spam drones

    // favourable spawn directions
    Direction[] n = {Direction.NORTH, Direction.NORTHWEST, Direction.NORTHEAST};
    Direction[] e = {Direction.EAST, Direction.NORTHEAST, Direction.SOUTHEAST};
    Direction[] s = {Direction.SOUTH, Direction.SOUTHEAST, Direction.SOUTHWEST};
    Direction[] w = {Direction.WEST, Direction.SOUTHWEST, Direction.NORTHWEST};

    Direction[] ne = {Direction.NORTHEAST, Direction.NORTH, Direction.EAST};
    Direction[] se = {Direction.SOUTHEAST, Direction.EAST, Direction.SOUTH};
    Direction[] sw = {Direction.SOUTHWEST, Direction.SOUTH, Direction.WEST};
    Direction[] nw = {Direction.NORTHWEST, Direction.WEST, Direction.NORTH};

    int sent = 0;
    int counter = 0;
    Direction latestDirSpawn;

    int ex = 0;

    public FulfillmentCenterController(RobotController rc) {
        getInfo(rc);
    }

    public void run() throws GameActionException {
        if (sent >= PRODUCTION_CAP) return;

        if (rc.getRoundNum() > 1700) { // just spam at this point, no need to conserve soup
            buildDrone();
        }

        if (rc.getRoundNum() % 10 == 0 && rc.getTeamSoup() > Math.min(ex, 400) + PlayerConstants.buildSoupRequirements(RobotType.DELIVERY_DRONE)) {
            buildDrone();
            ex += 100;
        }
    }

    public void buildDrone() throws GameActionException {
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
