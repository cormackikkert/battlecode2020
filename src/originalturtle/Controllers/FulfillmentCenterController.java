package originalturtle.Controllers;

import battlecode.common.*;
import originalturtle.CommunicationHandler;

import static originalturtle.CommunicationHandler.SCOUT_MESSAGE_COST;

public class FulfillmentCenterController extends Controller {
    boolean horizontal = false;
    boolean vertical = false;
    int sent = 0;
    public FulfillmentCenterController(RobotController rc) {
        this.rc = rc;
        this.communicationHandler = new CommunicationHandler(rc);
    }

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

                if (!horizontal && rc.getTeamSoup() >= 150 + SCOUT_MESSAGE_COST && tryBuild(RobotType.DELIVERY_DRONE, (thisPos.x > WSize / 2) ? Direction.WEST : Direction.EAST)) {
                    horizontal = true;
                    communicationHandler.sendScoutDirection(true);
                    sent++;
                }

                if (!vertical && rc.getTeamSoup() >= 150 + SCOUT_MESSAGE_COST && tryBuild(RobotType.DELIVERY_DRONE, (thisPos.x > HSize / 2) ? Direction.SOUTH : Direction.NORTH)) {
                    vertical = true;
                    communicationHandler.sendScoutDirection(false);
                    sent++;
                }
            }
        } else {
            for (Direction dir : directions)
                tryBuild(RobotType.DELIVERY_DRONE, dir);
        }
    }
}
