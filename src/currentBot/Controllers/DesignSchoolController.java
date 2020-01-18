package currentBot.Controllers;

import battlecode.common.*;

public class DesignSchoolController extends Controller {
    static final int DEFEND = 8;
    static final int HELP = 6; // like for clearing water at soup locations

    public enum State {
        DESTROY_ENEMY, // State used to spawn landscapers near enemy HQ
        DEFAULT        // TODO: make this useful
    }

    State currentState = State.DEFAULT;
    int builtLandscapers = 0;
    MapLocation location;

    public DesignSchoolController(RobotController rc) {
        getInfo(rc);
        this.location = rc.getLocation();

        for (RobotInfo robotInfo : rc.senseNearbyRobots()) {
            if (robotInfo.team == rc.getTeam().opponent() && robotInfo.type == RobotType.HQ) {
                allyHQ = robotInfo.location;
                currentState = State.DESTROY_ENEMY;
            }
        }
    }

    public void run() throws GameActionException {
        if (rc.getTeamSoup() > RobotType.DESIGN_SCHOOL.cost && builtLandscapers < DEFEND + HELP) {
            for (Direction dir : Direction.allDirections()) {
                if (tryBuild(RobotType.LANDSCAPER, dir)) {
                    int id = rc.senseRobotAtLocation(location.add(dir)).getID();

                    if (builtLandscapers < DEFEND) {
                        communicationHandler.landscapeDefend(id);
                    } else {
                        communicationHandler.landscapeHelp(id);
                    }
                    
//                    // for building wall later
//                    if (builtLandscapers < HELP) {
//                        communicationHandler.landscapeHelp(id);
//                    } else {
//                        communicationHandler.landscapeDefend(id);
//                    }
                    ++builtLandscapers;
                    break;
                }
            }
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
