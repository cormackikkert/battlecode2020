package originalturtle.Controllers;

import battlecode.common.*;

public class HQController extends Controller {
    public HQController(RobotController rc) {
        this.rc = rc;
    }

    public void run() throws GameActionException {
        System.out.println("team soup now at "+rc.getTeamSoup());
        if (rc.getRoundNum() > 5) return;
        for (Direction dir : directions)
            tryBuild(RobotType.MINER, dir);
    }
}
