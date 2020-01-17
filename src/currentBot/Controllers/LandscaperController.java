package currentBot.Controllers;

import battlecode.common.*;
import currentBot.CommunicationHandler;
import currentBot.MovementSolver;
import currentBot.SoupCluster;

import java.security.AllPermission;
import java.util.Map;

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
    int minHQElevation = 10;
    final int dirtLimit = RobotType.LANDSCAPER.dirtLimit;
    final int digHeight = 20;

    public LandscaperController(RobotController rc) {
        this.rc = rc;
        this.movementSolver = new MovementSolver(rc);
        this.communicationHandler = new CommunicationHandler(rc);
        getInfo(rc);

        for (RobotInfo robotInfo : rc.senseNearbyRobots()) {
            if (robotInfo.team == rc.getTeam().opponent() && robotInfo.type == RobotType.HQ) {
                enemyHQ = robotInfo.location;
                currentState = State.DESTROY;
            }
        }
    }

    public void run() throws GameActionException {
        // System.out.println("I am a " + currentState.toString());
        switch (currentState) {
            case PROTECTHQ:     execProtectHQ();    break;
            //case PROTECTSOUP:   execProtectSoup();  break;
            case DESTROY:       execDestroy();      break;
        }
    }

    public void execProtectHQ() throws GameActionException {
        if (allyHQ == null)
            allyHQ = communicationHandler.receiveAllyHQLoc();
        if (rc.getLocation().distanceSquaredTo(allyHQ) > 2) {
            goToLocationToDeposit(allyHQ);
        }
        // robot is currently on HQ wall
        MapLocation curr = rc.getLocation();
        Direction nextDir = nextDirection(curr);
        // System.out.println("New direction is " + nextDir.toString());

        if (Math.abs(rc.senseElevation(curr) - rc.senseElevation(curr.add(nextDir))) > 3) {
            if (level(nextDir)) {  // need to level dirt
                tryMove(nextDir);
            }
            return;
        }
        while (rc.getDirtCarrying() == 0) {
            tryDigRandom();
        }
        while (!rc.canDepositDirt(nextDir)) Clock.yield();
        rc.depositDirt(nextDir);
        tryMove(nextDir);
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

    boolean turnCW = true;
    Direction nextDirection(MapLocation loc) throws GameActionException {
        for (int i=0; i<8; ++i) {
            Direction d = directions[i];
            // System.out.println("Checking dir " + i + " gives " + d.toString());
            if (loc.equals(allyHQ.add(d))) {
                int j = turnCW ? (i+1)%8 : (i-1+8)%8;
                MapLocation newPos = allyHQ.add(directions[j]);
                if (!rc.onTheMap(newPos) || rc.isLocationOccupied(newPos)) {
                    turnCW = !turnCW;
                    j = turnCW ? (i+1)%8 : (i-1+8)%8;
                }
                newPos = allyHQ.add(directions[j]);
                if (rc.onTheMap(newPos)) return loc.directionTo(allyHQ.add(directions[j]));
                else break;
            }
        }
        return Direction.CENTER;
    }

    // TODO: implement for attack/defend
    Direction getDigDirection(Direction avoidDir) throws GameActionException {
        MapLocation curr = rc.getLocation();
        Direction buryDir = null;
        for (Direction dir : Direction.allDirections()) {
            if (rc.onTheMap(curr.add(dir)) && rc.isLocationOccupied(curr.add(dir))) {
                RobotInfo robot = rc.senseRobotAtLocation(curr.add(dir));
                if (robot.getTeam().isPlayer()) {
                    buryDir = dir; continue; }
            }
            if (dir != avoidDir && rc.canDigDirt(dir)) {
                if (allyHQ == null || curr.add(dir).distanceSquaredTo(allyHQ) > 2)
                    return dir;
            }
        }
        return buryDir;  // if no option but to bury an ally
    }

    boolean tryDigRandom() throws GameActionException {
        return tryDigRandom(Direction.CENTER);
    }
    boolean tryDigRandom(Direction avoidDir) throws GameActionException {
        while (!rc.isReady()) Clock.yield();
        Direction digDir = getDigDirection(avoidDir);
        if (digDir != null) {
            rc.digDirt(digDir); Clock.yield();
            return true;
        } else {
            if (tryMove(randomDirection())) Clock.yield();
            return false;
        }
    }

    // used for landscaper to climb up to HQ wall
    MapLocation lastTried;
    void goToLocationToDeposit(MapLocation goal) throws GameActionException {
        System.out.println("Going to tile to protect HQ at " + goal.toString());
        while (rc.getLocation().distanceSquaredTo(goal) > 2) {
            Direction dir = dirToGoal(goal);
            System.out.println("Received direction " + dir.toString());
            MapLocation curr = rc.getLocation();
            if (curr.add(dir).equals(lastTried)) {
                do { dir = (rotateCW) ? dir.rotateRight() : dir.rotateLeft();
                    System.out.println("Checking dir " + dir.toString());
                } while (isLandscaperObstacle(curr, curr.add(dir)));
                System.out.println("New dir " + dir.toString());
            }
            if (Math.abs(rc.senseElevation(curr) - rc.senseElevation(curr.add(dir))) > 3) {
                if (!level(dir)) {  // need to level dirt
                    // if could not level, try another path
                    previous = curr.add(dir);
                    goToLocationToDeposit(goal);
                }
            }
            tryMove(dir);
            lastTried = curr.add(dir);
        }
    }
    // levels the dirt in given direction compared to current loc
    boolean level(Direction dir) throws GameActionException {
        MapLocation curr = rc.getLocation();
        MapLocation pos = curr.add(dir);
        System.out.println("Trying to level tile at " + pos.toString());
        int numMoves = 0;
        int elevation = rc.senseElevation(pos);
        int currElevation = rc.senseElevation(curr);
        if (currElevation < elevation) {  // currently in a hole
            while (rc.senseElevation(curr) < rc.senseElevation(pos)) {
                if (rc.getDirtCarrying() == 0) tryDigRandom();
                if (rc.canDepositDirt(Direction.CENTER)) rc.depositDirt(Direction.CENTER);
                Clock.yield();
            }
        } else {  // need to go to lower ground
            while (currElevation > rc.senseElevation(pos)) {
                if (rc.getDirtCarrying() == 0) tryDigRandom();
                if (rc.canDepositDirt(dir)) rc.depositDirt(dir);
                numMoves++; if (numMoves > digHeight) return false;
                Clock.yield();
            }
        }
        System.out.println("Done levelling at " + pos.toString());
        return true;
    }

    // movement solver for landscaper
    // which ignores small elevation changes (< digHeight) on path to goal
    boolean rotateCW = true;
    MapLocation previous;
    Direction dirToGoal(MapLocation goal) throws GameActionException {
        if (!rc.isReady()) Clock.yield(); // canMove considers cooldown time

        MapLocation from = rc.getLocation();
        Direction dir = from.directionTo(goal);

        int changes = 0;

        // while obstacle ahead, keep rotating
        while (isLandscaperObstacle(from, from.add(dir))) {
            System.out.println(dir.toString() + " is an obstacle, rotated " + changes);
            if (!onTheMap(rc.getLocation().add(dir))) {
                rotateCW = !rotateCW; previous = null;
                ++changes;
            }
            dir = (rotateCW) ? dir.rotateRight() : dir.rotateLeft();
            // if blocked in every direction, stop rotating
            if (changes > 8) return Direction.CENTER;
        }

        previous = from;
        return dir;
    }

    // elevation is not an obstacle
    boolean isLandscaperObstacle(MapLocation from, MapLocation to) throws GameActionException {
        // obstacle if is occupied by building, not on map, or previous point
        if (!rc.onTheMap(to)) return true;
        if (rc.isLocationOccupied(to)) {
            RobotType robot = rc.senseRobotAtLocation(to).type;
            if (robot.isBuilding()) return true;
        }
        if (Math.abs(rc.senseElevation(from)-rc.senseElevation(to)) > digHeight)
            return true;
        return rc.senseFlooding(to) || to.equals(previous);
    }


}
