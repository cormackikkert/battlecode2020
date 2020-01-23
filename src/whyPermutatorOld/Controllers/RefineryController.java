package whyPermutatorOld.Controllers;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.RobotController;
import battlecode.common.RobotType;

public class RefineryController extends Controller {
    public RefineryController(RobotController rc) {
        this.rc = rc;
    }

    public void run() throws GameActionException {
        for (Direction dir : directions)
            tryBuild(RobotType.MINER, dir);
    }
}
