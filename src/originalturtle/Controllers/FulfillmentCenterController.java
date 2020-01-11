package originalturtle.Controllers;

import battlecode.common.*;

public class FulfillmentCenterController extends Controller {
    boolean horizontal = false;
    boolean vertical = false;
    public FulfillmentCenterController(RobotController rc) { this.rc = rc; }

    Direction[] diagonal = {Direction.NORTHEAST, Direction.NORTHWEST, Direction.SOUTHEAST, Direction.SOUTHWEST};
    Direction[] straight = {Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.EAST};

    public void run() throws GameActionException {

        // send enemy scouters
        if (enemyHQ == null && (!horizontal || !vertical)) {
            // closest fulfillment center to hq sends scouters
            RobotInfo[] allies = rc.senseNearbyRobots(10, rc.getTeam());
            boolean closest = false;
            for (RobotInfo ally : allies) {
                if (ally.getType() == RobotType.HQ) {
                    closest = true;
                    break;
                }
            }
            if (closest) {

                int HSize = this.rc.getMapHeight();
                int WSize = this.rc.getMapWidth();

                MapLocation thisPos = this.rc.getLocation();

                if (!horizontal) {
                    horizontal = tryBuild(RobotType.DELIVERY_DRONE, (thisPos.x > WSize / 2) ? Direction.WEST : Direction.EAST);
                    if (horizontal) {
                        System.out.println("success");
                    }
                }

                if (!vertical) {
                    vertical = tryBuild(RobotType.DELIVERY_DRONE, (thisPos.x > HSize / 2) ? Direction.SOUTH : Direction.NORTH);
                    if (vertical) {
                        System.out.println("success");
                    }
                }
            }
        } else {
            System.out.println("diagonal");
            for (Direction dir : diagonal)
                tryBuild(RobotType.DELIVERY_DRONE, dir);
        }
    }
}
