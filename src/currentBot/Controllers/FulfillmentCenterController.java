package currentBot.Controllers;

import battlecode.common.*;
import currentBot.CommunicationHandler;

public class FulfillmentCenterController extends Controller {

    static final int PRODUCTION_CAP = Integer.MAX_VALUE; // spam drones

    // TODO : favourable directions to spawn

    int sent = 0;
    Direction latestDirSpawn;

    public FulfillmentCenterController(RobotController rc) {
        getInfo(rc);
    }

    public void run() throws GameActionException {
        if (sent >= PRODUCTION_CAP) return;

        for (Direction direction : directions) {
            if (tryBuild(RobotType.DELIVERY_DRONE, direction)) {
                sent++;
            }
        }
    }

    public boolean tryBuildA(Direction direction) throws GameActionException {
        for (int i = 0; i < 8; i++) {
            if (i % 2 == 0) {
                for (int j = 0; j < i; j++) {
                    direction = direction.rotateLeft();
                }
            } else {
                for (int j = 0; j < i; j++) {
                    direction = direction.rotateRight();
                }
            }
            i++;
            if (tryBuild(RobotType.DELIVERY_DRONE, direction)) {
                latestDirSpawn = direction;
                return true;
            }
        }
        return false;
    }
}
