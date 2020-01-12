package originalturtle.Controllers;

import battlecode.common.*;
import originalturtle.CommunicationHandler;

public class FulfillmentCenterController extends Controller {

    static final int PRODUCTION_CAP = 8;

    boolean horizontal = false;
    boolean vertical = false;

    int sent = 0;

    public FulfillmentCenterController(RobotController rc) {
        getInfo(rc);
    }

    public void run() throws GameActionException {
        if (sent >= PRODUCTION_CAP) return; // cap to how many drones to build as to not waste resources

        // send enemy scouters
        if (enemyHQ == null && (!horizontal || !vertical)) {
            // closest fulfillment center to hq sends scouters
            RobotInfo[] allies = rc.senseNearbyRobots(10, rc.getTeam());
            boolean closest = false;
            for (RobotInfo ally : allies) {
                if (ally.getType() == RobotType.HQ) {
                    closest = true;
                    allyHQ = ally.getLocation();
                    break;
                }
            }
            if (closest) {

                int HSize = this.rc.getMapHeight();
                int WSize = this.rc.getMapWidth();

                MapLocation thisPos = this.rc.getLocation();

                if (!horizontal && rc.getTeamSoup() >= 150 + CommunicationHandler.SCOUT_MESSAGE_COST && tryBuild(RobotType.DELIVERY_DRONE, (thisPos.x > WSize / 2) ? Direction.WEST : Direction.EAST)) {
                    horizontal = true;
                    communicationHandler.sendScoutDirection(allyHQ, true);
                    sent++;
                }

                if (!vertical && rc.getTeamSoup() >= 150 + CommunicationHandler.SCOUT_MESSAGE_COST && tryBuild(RobotType.DELIVERY_DRONE, (thisPos.y > HSize / 2) ? Direction.SOUTH : Direction.NORTH)) {
                    vertical = true;
                    communicationHandler.sendScoutDirection(allyHQ, false);
                    sent++;
                }
            }
        } else {
            for (Direction dir : directions) {
                if (tryBuild(RobotType.DELIVERY_DRONE, dir)) {
                    sent++;
                }
            }
        }
    }
}
