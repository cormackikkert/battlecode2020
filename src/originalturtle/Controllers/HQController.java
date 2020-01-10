package originalturtle.Controllers;

import battlecode.common.*;

public class HQController extends Controller {
    public HQController(RobotController rc) {
        this.rc = rc;
    }

    public void run() throws GameActionException {
        if (this.rc.getRoundNum() == 1) {
            /*
                Send first 2 miners on scouting mission to determine which axis the board is symmetrical
                (If HQ is positioned on center line we just need 1)
            */

            int HSize = this.rc.getMapHeight();
            int WSize = this.rc.getMapWidth();

            MapLocation HQPos = this.rc.getLocation();
            if (HQPos.x == WSize / 2 + 1 && WSize % 2 == 1) {
                // Cant be horizontally symmetric
            } else if (HQPos.y == HSize / 2 + 1 && HSize % 2 == 1) {
                // Cant be vertically symmetric
            } else {
                // Can be anything
                rc.buildRobot(RobotType.MINER, (HQPos.x > WSize / 2) ? Direction.WEST : Direction.EAST);
                Clock.yield();
                rc.buildRobot(RobotType.MINER, (HQPos.y > HSize / 2) ? Direction.SOUTH : Direction.NORTH);
            }
        }

        System.out.println("team soup now at "+rc.getTeamSoup());
        if (rc.getRoundNum() % 5 == 0)
            for (Direction dir : directions)
                tryBuild(RobotType.MINER, dir);
    }
}
