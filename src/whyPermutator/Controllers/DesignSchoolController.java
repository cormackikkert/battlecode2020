package whyPermutator.Controllers;

import battlecode.common.*;

import static whyPermutator.Controllers.PlayerConstants.*;

public class DesignSchoolController extends Controller {

    public enum State {
        DESTROY_ENEMY, // State used to spawn landscapers near enemy HQ
        DEFAULT        // TODO: make this useful
    }

    State currentState = State.DEFAULT;
    int builtLandscapers = 0;
    MapLocation location;

    int ex = 0;

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
        if ((rc.getTeamSoup() > Math.min(400, ex) + PlayerConstants.buildSoupRequirements(RobotType.DESIGN_SCHOOL)) && (builtLandscapers < (DEFEND + HELP))
        && rc.getRoundNum() % 10 == 5) {
            for (Direction dir : getDirections()) {
                if (tryBuild(RobotType.LANDSCAPER, dir)) {
                    int id = rc.senseRobotAtLocation(location.add(dir)).getID();
                    ex += 100;
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

        if (rc.getRoundNum() > 450 && builtLandscapers < (DEFEND + HELP)) {
            for (Direction dir : getDirections()) {
                if (tryBuild(RobotType.LANDSCAPER, dir)) {
                    int id = rc.senseRobotAtLocation(location.add(dir)).getID();
                    ex += 100;
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
            for (Direction dir : getDirections()) {
                if (tryBuild(RobotType.LANDSCAPER, dir)) {++builtLandscapers; break;}
            }
        }
    }
}