package originalturtle.Controllers;

import battlecode.common.*;

public class HQController extends Controller {
    public HQController(RobotController rc) {
        this.rc = rc;
    }

    public void run() throws GameActionException {
        for (Direction dir : directions)
            tryBuild(RobotType.MINER, dir);
    }
}
