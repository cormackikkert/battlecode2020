package whyPermutator.Controllers;

import battlecode.common.*;

public class NetGunController extends Controller {
    public NetGunController(RobotController rc) {
        getInfo(rc);
    }

    public void run() throws GameActionException {
        scanRobots();
        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == RobotType.DELIVERY_DRONE) {
                tryShoot(enemy);
            }
        }
    }
}
