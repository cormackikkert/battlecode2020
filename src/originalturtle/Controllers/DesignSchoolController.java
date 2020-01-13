package originalturtle.Controllers;

import battlecode.common.*;

public class DesignSchoolController extends Controller {

    public enum State {
        DESTROY_ENEMY, // State used to spawn landscapers near enemy HQ
        DEFAULT        // TODO: make this useful
    }

    State currentState = State.DEFAULT;
    int builtLandscapers = 0;

    public DesignSchoolController(RobotController rc) {
        this.rc = rc;

        for (RobotInfo robotInfo : rc.senseNearbyRobots()) {
            if (robotInfo.team == rc.getTeam().opponent() && robotInfo.type == RobotType.HQ) {
                allyHQ = robotInfo.location;
                currentState = State.DESTROY_ENEMY;
            }
        }
    }

    public void run() throws GameActionException {
        switch (currentState) {
            case DESTROY_ENEMY: execDestroyEnemy(); break;
        }
    }

    public void execDestroyEnemy() throws GameActionException {
        if (rc.getTeamSoup() > RobotType.DESIGN_SCHOOL.cost && builtLandscapers < 4) {
            for (Direction dir : Direction.allDirections()) {
                if (tryBuild(RobotType.LANDSCAPER, dir)) {++builtLandscapers; break;}
            }
        }
    }
}
