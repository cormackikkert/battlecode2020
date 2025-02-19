package whyPermutator.Controllers;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.RobotController;
import battlecode.common.RobotType;

public class VaporatorController extends Controller {
    public VaporatorController(RobotController rc) {
        this.rc = rc;
    }

    public void run() throws GameActionException {
        for (Direction dir : directions)
            tryBuild(RobotType.MINER, dir);
    }
}
