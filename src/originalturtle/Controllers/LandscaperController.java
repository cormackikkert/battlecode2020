package originalturtle.Controllers;

import battlecode.common.*;
import originalturtle.*;

public class LandscaperController extends Controller {
    MovementSolver movementSolver;
    CommunicationHandler communicationHandler;

    enum State {
        PROTECTHQ,  // builds a wall of specified height around HQ
        //PROTECTSOUP,  // builds wall around a soup cluster
        DESTROY  // piles dirt on top of enemy building
    }
    State currentState = State.PROTECTHQ;
    SoupCluster currentSoupCluster; // build wall around this
    int minHQElevation = 5;
    final int dirtLimit = RobotType.LANDSCAPER.dirtLimit;

    public LandscaperController(RobotController rc) {
        this.rc = rc;
        this.movementSolver = new MovementSolver(rc);
        this.communicationHandler = new CommunicationHandler(rc);

        for (RobotInfo robotInfo : rc.senseNearbyRobots()) {
            if (robotInfo.team == rc.getTeam().opponent() && robotInfo.type == RobotType.HQ) {
                enemyHQ = robotInfo.location;
                currentState = State.DESTROY;
            }
        }
    }

    public void run() throws GameActionException {
        System.out.println("I am a " + currentState.toString());
        if (allyHQ == null)
            allyHQ = communicationHandler.receiveAllyHQLoc();
        switch (currentState) {
            case PROTECTHQ:     execProtectHQ();    break;
            //case PROTECTSOUP:   execProtectSoup();  break;
            case DESTROY:       execDestroy();      break;
        }
    }

    public void execProtectHQ() throws GameActionException {
        // assuming all tiles around HQ are on the map
        for (Direction d : Direction.allDirections()) {
            MapLocation toCover = allyHQ.add(d);
            goToLocationToSense(toCover);
            int elevation = rc.senseElevation(toCover);
            while (rc.getDirtCarrying() < Math.min(minHQElevation - elevation, dirtLimit)) {
                Direction digDir = getDigDirection();
                if (digDir != null) {
                    rc.digDirt(digDir); Clock.yield();
                } else {
                    if (tryMove(randomDirection())) Clock.yield();
                }
            }
            goToLocationToDeposit(toCover);
            Direction dir = rc.getLocation().directionTo(toCover);
            for (int i = 0; i < rc.getDirtCarrying(); i++) {
                while (!rc.isReady()) Clock.yield();
                rc.depositDirt(dir);
            }
        }

    }

    public void execDestroy() throws GameActionException {
        if (getDistanceSquared(rc.getLocation(), enemyHQ) > 2) {
            tryMove(movementSolver.directionToGoal(enemyHQ));
        } else {
            Direction toEnemy = rc.getLocation().directionTo(enemyHQ);

            // We are already close enough to the enemy HQ
            if (rc.getDirtCarrying() < RobotType.LANDSCAPER.dirtLimit) {
                for (Direction dir : Direction.allDirections()) {
                    if (rc.canDigDirt(dir) && !rc.getLocation().add(dir).equals(enemyHQ)) {
                        rc.digDirt(dir); break;
                    }
                }
            } else if (rc.canDepositDirt(toEnemy)){
                rc.depositDirt(toEnemy);
            }
        }
    }

    // TODO: implement for attack/defend
    Direction getDigDirection() {
        MapLocation curr = rc.getLocation();
        for (Direction dir : Direction.allDirections()) {
            if (rc.canDigDirt(dir)) {
                if (allyHQ == null || curr.add(dir).distanceSquaredTo(allyHQ) > 2)
                    return dir;
            }
        }
        return null;
    }


}
