package currentBot.Controllers;

import battlecode.common.*;
import currentBot.CommunicationHandler;
import currentBot.MovementSolver;
import currentBot.SoupCluster;

import java.security.AllPermission;
import java.util.ArrayList;
import java.util.Map;

public class LandscaperController extends Controller {
    MovementSolver movementSolver;
    CommunicationHandler communicationHandler;

    enum State {
        PROTECTHQ,  // builds a wall of specified height around HQ
        //PROTECTSOUP,  // builds wall around a soup cluster
        DESTROY,  // piles dirt on top of enemy building
        KILLUNITS // Kills units around HQ
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
                if (Math.random() > 0.5)
                    currentState = State.DESTROY;
                else
                    currentState = State.KILLUNITS;
            }
        }
    }

    public void run() throws GameActionException {
        // System.out.println("I am a " + currentState.toString());
        switch (currentState) {
            case PROTECTHQ:     execProtectHQ();    break;
            case KILLUNITS: execKillUnits();  break;
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

    public void execKillUnits() throws GameActionException {
        // Prioritize killing in this order
        MapLocation adjacentPos = null;
        MapLocation netGunPos = null;
        MapLocation designSchoolPos = null;
        MapLocation fulfillmentCenterPos = null;

        for (RobotInfo robot : rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rc.getTeam().opponent())) {
            if (robot.type == RobotType.NET_GUN) netGunPos = robot.location;
            else if (robot.type == RobotType.DESIGN_SCHOOL) designSchoolPos = robot.location;
            else if (robot.type == RobotType.FULFILLMENT_CENTER) fulfillmentCenterPos = robot.location;

            if (netGunPos != null && netGunPos.isAdjacentTo(rc.getLocation())) adjacentPos = netGunPos;
            else if (designSchoolPos != null && designSchoolPos.isAdjacentTo(rc.getLocation())) adjacentPos = netGunPos;
            else if (fulfillmentCenterPos != null && fulfillmentCenterPos.isAdjacentTo(rc.getLocation())) adjacentPos = netGunPos;
        }

        // Kill an adjacent one
        if (adjacentPos != null) {
            if (rc.getDirtCarrying() > 0 && rc.canDepositDirt(rc.getLocation().directionTo(adjacentPos))) {
                rc.depositDirt(rc.getLocation().directionTo(adjacentPos));
            } else {
                for (Direction dir : Direction.allDirections()) {
                    MapLocation pos = rc.getLocation().add(dir);
                    RobotInfo robot = rc.senseRobotAtLocation(pos);
                    if (robot != null &&
                        robot.getTeam() == rc.getTeam().opponent() && (
                                robot.type == RobotType.FULFILLMENT_CENTER ||
                                robot.type == RobotType.DESIGN_SCHOOL ||
                                robot.type == RobotType.NET_GUN)
                            ) continue;

                    if (rc.canDigDirt(dir)) {
                        rc.digDirt(dir);
                        break;
                    }
                }
            }
        } else {
            MapLocation enemy = (netGunPos != null) ? netGunPos : ((designSchoolPos != null) ? designSchoolPos : fulfillmentCenterPos);
            if (enemy != null) {
                tryMove(movementSolver.directionToGoal(enemy));
            } else {
                if (getChebyshevDistance(rc.getLocation(), allyHQ) < 2) {
                    tryMove(movementSolver.directionToGoal(rc.getLocation().add(rc.getLocation().directionTo(allyHQ).opposite())));
                } else if (getChebyshevDistance(rc.getLocation(), allyHQ) > 4) {
                    tryMove(movementSolver.directionToGoal(allyHQ));
                } else {
                    // Dig dirt so depositing is fast
                    for (Direction dir : Direction.allDirections()) {
                        if (dir == rc.getLocation().directionTo(allyHQ)) continue;
                        if (rc.canDigDirt(dir)) {
                            rc.digDirt(dir);
                            return;
                        }
                    }
                }
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

    // returns cell with minimum elevation
    Direction nextDirection(MapLocation loc) throws GameActionException {
        Direction finalDir = null;
        int elevation = Integer.MAX_VALUE;
        for (Direction d : Direction.allDirections()) {
            MapLocation newPos = loc.add(d);
            int dist = allyHQ.distanceSquaredTo(newPos);
            if (rc.onTheMap(newPos) && dist <= 2 && dist > 0) {
                if (rc.senseElevation(newPos) < elevation) {
                    elevation = rc.senseElevation(newPos);
                    finalDir = d;
                }
            }
        }
        return finalDir;
    }

    // TODO: implement for attack/defend
    Direction getDigDirection(Direction avoidDir) throws GameActionException {
        MapLocation curr = rc.getLocation();
        Direction buryDir = null;
        Direction highEl = null; int el = Integer.MIN_VALUE;
        for (Direction dir : directions) {
            MapLocation digPos = curr.add(dir);
            if (dir != avoidDir && rc.canDigDirt(dir)) {
                if (rc.senseElevation(digPos) > el) {
                    highEl = dir; el = rc.senseElevation(digPos);
                }
                // avoid burying an ally robot
                if (rc.isLocationOccupied(digPos)) {
                    RobotInfo robot = rc.senseRobotAtLocation(digPos);
                    if (robot.getTeam().isPlayer() && digPos.distanceSquaredTo(allyHQ)>2) {
                        buryDir = dir; continue;
                    }
                }
                if (allyHQ == null || digPos.distanceSquaredTo(allyHQ) > 2)
                    return dir;
            }
        }
        if (buryDir == null) return highEl;  // if no access to cell outside wall
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
    void goToLocationToDeposit(MapLocation goal) throws GameActionException {
        System.out.println("Going to tile to protect HQ at " + goal.toString());
        while (rc.getLocation().distanceSquaredTo(goal) > 5) {
            tryMove(movementSolver.directionToGoal(goal));
        }
        while (rc.getLocation().distanceSquaredTo(goal) > 2) {
            Direction dir = dirToGoal(goal);
            System.out.println("Received direction " + dir.toString());
            MapLocation curr = rc.getLocation();
            if (Math.abs(rc.senseElevation(curr) - rc.senseElevation(curr.add(dir))) > 3) {
                if (!level(dir)) {  // need to level dirt
                    // if could not level, try another path
                    previous.add(curr.add(dir));
                    continue;
                }
            }
            tryMove(dir);
            if (!tryMove(dir)) previous.add(curr.add(dir));
            System.out.println("Tried to move");
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
            while (rc.senseElevation(curr) > rc.senseElevation(pos)) {
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
    ArrayList<MapLocation> previous = new ArrayList<>(8);
    Direction dirToGoal(MapLocation goal) throws GameActionException {
        if (!rc.isReady()) Clock.yield(); // canMove considers cooldown time
        MapLocation from = rc.getLocation();
        Direction dir = from.directionTo(goal);

        // while obstacle ahead, keep rotating
        Direction[] closeDirs = getClosestDirections(dir); int i = 0;
        while (isLandscaperObstacle(from, from.add(dir)) && i<7) {
            System.out.println(dir.toString() + " is an obstacle");
            dir = closeDirs[i]; i++;
        }
        if (isLandscaperObstacle(from, from.add(dir))) return Direction.CENTER;
        else {
            previous.add(from);
            return dir;
        }
    }

    boolean isLandscaperObstacle(MapLocation from, MapLocation to) throws GameActionException {
        // obstacle if is occupied by building, not on map, or previous point
        if (previous.size() >= 8) previous.clear();
        if (!rc.onTheMap(to)) return true;
        return rc.isLocationOccupied(to) || rc.senseFlooding(to) || previous.contains(to);
    }

    Direction[] getClosestDirections(Direction d) {
        return new Direction[]{d.rotateLeft(), d.rotateRight(), d.rotateLeft().rotateLeft(),
        d.rotateRight().rotateRight(), d.opposite().rotateRight(), d.opposite().rotateLeft(),
        d.opposite()};
    }

}
