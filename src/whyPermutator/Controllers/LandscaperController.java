package whyPermutator.Controllers;

import battlecode.common.*;
import whyPermutator.*;

import java.util.ArrayList;
import java.util.LinkedList;

import static whyPermutator.Controllers.PlayerConstants.*;

// Search surroundings Soup doesnt work (its an easy fix just cant be bothered)

public class LandscaperController extends Controller {

    public enum State {
        PROTECTHQ,  // builds a wall of specified height around HQ
        //PROTECTSOUP,  // builds wall around a soup cluster
        DESTROY,  // piles dirt on top of enemy building
        DEFEND,
        ATTACK,
        ROAM,
        REMOVE_WATER,
        KILLUNITS, // Kills units around HQ
        ELEVATE_BUILDING,
        DESTROY_WALL, // also can kill hq,
        ELEVATE_SELF;
    }
    public State currentState = State.REMOVE_WATER;
    public SoupCluster currentSoupCluster; // build wall around this
    final int dirtLimit = RobotType.LANDSCAPER.dirtLimit;
    final int digHeight = 20;
    MapLocation currentSoupSquare;

    int lastRound = 1;

    // Integer (instead of int) so that we can use null as unsearched

    // Arrays that are filled as each miner searches the map
    Integer[][] soupCount = null;
    Integer[][] elevationHeight = null;
    Integer[][] buildMap = null; // Stores what buildings have been built and where
    boolean[][] dumped;

    boolean[][] searchedForSoupCluster = null; // Have we already checked if this node should be in a soup cluster
    RingQueue<MapLocation> reachQueue = new RingQueue<>(PlayerConstants.SEARCH_DIAMETER * PlayerConstants.SEARCH_DIAMETER);

    LinkedList<SoupCluster> soupClusters = new LinkedList<>();

    public LandscaperController(RobotController rc) {
        int born = rc.getRoundNum();
        getInfo(rc);

        try {
            while (rc.getRoundNum() < born + 9) readBlocks();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

        if (landscapersOnWall < 8)
            currentState = State.PROTECTHQ;
        else
            currentState = State.KILLUNITS;


//        int defenders = 0;
        for (RobotInfo robotInfo : rc.senseNearbyRobots()) {
            if (robotInfo.team == rc.getTeam().opponent() && robotInfo.type == RobotType.HQ) {
                enemyHQ = robotInfo.location;
                if (Math.random() > 0.5)
                    currentState = State.DESTROY;
                else
                    currentState = State.KILLUNITS;
            }
//            if (robotInfo.team == rc.getTeam() && robotInfo.type == RobotType.LANDSCAPER) {
//                defenders++;
//            }
        }

//        if (defenders > 4) {
//            currentState = State.ROAM;
//        }
    }

    public void run() throws GameActionException {

//        if (currentState != State.PROTECTHQ && rc.getRoundNum() >= ELEVATE_TIME) {
//            currentState = State.ELEVATE_BUILDING;
//        }
//        if (!rc.getLocation().isWithinDistanceSquared(allyHQ, 8) && rc.getRoundNum() >= ELEVATE_TIME) {
//            currentState = State.ELEVATE_BUILDING;
//        }

        hqInfo(); // includes scanning robots
        scanNetGuns();
        communicationHandler.solveEnemyHQLocWithGhosts();

        // search for enemy units to kill
        boolean existsEnemyBuilding = false;
        for (RobotInfo robot : rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rc.getTeam().opponent())) {
            if (robot.type == RobotType.FULFILLMENT_CENTER ||
                    robot.type == RobotType.DESIGN_SCHOOL ||
                    robot.type == RobotType.NET_GUN) existsEnemyBuilding = true;
        }

        // keep track of old state
        if (!(currentState == State.PROTECTHQ && rc.getRoundNum() > START_BUILD_WALL) && existsEnemyBuilding) {
            execKillUnits();
            return;
        }

        if (enemyHQ != null) {
            if (rc.getLocation().isWithinDistanceSquared(enemyHQ, 8)) {
                currentState = State.DESTROY_WALL; // also kills hq
            }
        } else {
            for (RobotInfo robotInfo : rc.senseNearbyRobots()) {
                if (robotInfo.team == rc.getTeam().opponent() && robotInfo.type == RobotType.HQ) {
                    enemyHQ = robotInfo.location;
                    currentState = State.DESTROY_WALL; // also kills hq
                }
            }
        }

        System.out.println("I am a " + currentState.toString());
        switch (currentState) {
            case PROTECTHQ: execProtectHQ();      break;
            //case PROTECTSOUP:   execProtectSoup();  break;
            case DESTROY:       execDestroy();    break;
            case REMOVE_WATER: execRemoveWater(); break;
            case KILLUNITS: execKillUnits();      break;
            case ELEVATE_BUILDING: execElevate(); break;
            case DESTROY_WALL: execDestroyWall(); break;
            case ELEVATE_SELF: execElevateSelf(); break;
//            case ROAM: movementSolver.windowsRoam(); break;
        }

        readBlocks();
    }

    public void execElevateSelf() throws GameActionException {
        MapLocation location = rc.getLocation();
        if (rc.getDirtCarrying() == 0) {
            for (Direction direction : directions) {
                MapLocation location1 = location.add(direction);
                if (rc.canSenseLocation(location1) &&
                        (rc.senseRobotAtLocation(location1) == null ||
                                rc.senseRobotAtLocation(location1).getTeam() != ALLY)) {
                    if (rc.canDigDirt(direction)) {
                        rc.digDirt(direction);
                        break;
                    }
                }
            }
        }

        if (rc.getDirtCarrying() > 0) {
            if (rc.canDepositDirt(Direction.CENTER)) {
                rc.depositDirt(Direction.CENTER);
            }
        }
    }

    boolean askFriend = false;
    Direction minerDirection = null;
    MapLocation minerLocation = null;
    Company confirmedReq = null;
    public void execElevate() throws GameActionException { // DO NOT MOVE
        MapLocation mapLocation = rc.getLocation();
        if (minerDirection == null) {

            // asking for help if not already
            if (!askFriend) {
                communicationHandler.sendCompany(new Company(mapLocation, -1));
                askFriend = true;
            } else if (confirmedReq == null) {
                for (Transaction tx : rc.getBlock(rc.getRoundNum() - 1)) {
                    int[] mess = tx.getMessage();
                    if (communicationHandler.identify(mess) == CommunicationHandler.CommunicationType.ASK_COMPANY_ACK) {
                        Company ack = communicationHandler.getCompanyAck(mess);
                        if (ack.landscaperPos.equals(rc.getLocation())) {
                            confirmedReq = ack;
                            break;
                        }
                    }
                }
                return;

            }

            // checking if helper miner is here
            if (rc.canSenseRobot(confirmedReq.minerID) && isAdjacentTo(rc.senseRobot(confirmedReq.minerID).getLocation())) {
                minerLocation = rc.senseRobot(confirmedReq.minerID).getLocation();
                minerDirection = rc.getLocation().directionTo(minerLocation);
            }

            if (rc.getRoundNum() >= ELEVATE_SELF_IF_LONELY) {
                if (rc.getDirtCarrying() == 0) {
                    for (Direction dir : getDirections()) {
                        MapLocation digHere = mapLocation.add(dir);
                        RobotInfo robotInfo = rc.senseRobotAtLocation(digHere);
                        if (rc.canDigDirt(dir)
                                && (digHere.x + digHere.y) % 2 == 1
                                && (robotInfo == null || robotInfo.getTeam() != ALLY)
                        ) {
                            rc.digDirt(dir);
                            System.out.println("dig for center dig");
                        }
                    }
                } else {
                    if (rc.canDepositDirt(Direction.CENTER)) {
                        rc.depositDirt(Direction.CENTER);
                        System.out.println("dump for me");
                    }
                }

            }

        } else {
            if (rc.senseElevation(mapLocation) >= ELEVATE_ENOUGH && rc.senseElevation(minerLocation) >= ELEVATE_ENOUGH) {
                System.out.println("try to die");
                rc.disintegrate();
            }

            if (rc.getDirtCarrying() == 0) {
                System.out.println("want to dig for "+minerLocation);
                for (Direction dir : getDirections()) {
                    MapLocation digHere = mapLocation.add(dir);
                    RobotInfo robotInfo = rc.senseRobotAtLocation(digHere);
                    if (rc.canDigDirt(dir)
//                            && (digHere.x + digHere.y) % 2 == 1
                            && !digHere.equals(minerLocation)
                            && (robotInfo == null || robotInfo.getTeam() != ALLY)
                                    ) {
                        rc.digDirt(dir);
                        System.out.println("dig for center dig");
                    }
                }
            } else {
                if (rc.senseElevation(mapLocation) > rc.senseElevation(minerLocation)) {
                    if (rc.canDepositDirt(minerDirection)) {
                        rc.depositDirt(minerDirection);
                        System.out.println("dump for friend");
                    }
                } else {
                    if (rc.canDepositDirt(Direction.CENTER)) {
                        rc.depositDirt(Direction.CENTER);
                        System.out.println("dump for me");
                    }
                }
            }
        }
    }
    final int numWallers = 8;
    final int roundToLevelWall = 450;
    public void execProtectHQ() throws GameActionException {

        if (allyHQ == null) return;

        int walled = 0;
        int maximum = 8;
        if (rc.canSenseLocation(allyHQ)) {
            for (RobotInfo rb : rc.senseNearbyRobots(allyHQ, 2,rc.getTeam())) {
                if (rb.type.equals(RobotType.LANDSCAPER)) walled++;
            }
        }
        for (Direction dir : getDirections()) {
            if (dir == Direction.CENTER) continue;
            if (!rc.onTheMap(allyHQ.add(dir))) --maximum;
        }
        if (!rc.getLocation().isAdjacentTo(allyHQ)) {
            if (walled == numWallers) {  // if already have all 8 landscapers building wall
                currentState = State.REMOVE_WATER; // TODO : or assign another role
                return;
            }

            boolean almostFlood = rc.senseElevation(rc.getLocation()) < GameConstants.getWaterLevel(rc.getRoundNum()) + 100 && rc.getRoundNum() > 1000;

            // otherwise go to HQ
            if (getChebyshevDistance(rc.getLocation(), allyHQ) > 10 && !almostFlood) {
                getHitchHike(rc.getLocation(), allyHQ);
            } else if (!almostFlood) {
                goToLocationToDeposit(allyHQ);
            } else {
                currentState = State.ELEVATE_SELF;
                execElevateSelf();
            }
            return;
        }
        if (walled == maximum) {
            if (rc.getDirtCarrying() == 0) newDig();
            else newDeposit();
        } else {
            // robot is currently on HQ wall
            MapLocation curr = rc.getLocation();
            if (rc.senseRobotAtLocation(allyHQ).getDirtCarrying() > 0 && rc.canDigDirt(curr.directionTo(allyHQ))) {
                rc.digDirt(rc.getLocation().directionTo(allyHQ));
                return;
            }
            if (rc.getRoundNum() <= 450 && walled < Math.min(maximum, numWallers)) {  // wait for more wallers
                return;
            }
            Direction nextDir = nextDirection(curr);
            // System.out.println("New direction is " + nextDir.toString());

            while (rc.getDirtCarrying() == 0) {
                tryDigRandom();
            }
            if (walled == maximum) {
                rc.depositDirt(Direction.CENTER);
            } else {
                while (!rc.canDepositDirt(nextDir)) Clock.yield();
                rc.depositDirt(nextDir);
                if (shouldMoveOnWall(curr.add(nextDir))) tryMove(nextDir);
            }
        }
    }

    public void execDestroyWall() throws GameActionException {
        if (rc.getLocation().isAdjacentTo(enemyHQ)) {
            if (rc.getDirtCarrying() == 0) {
                for (Direction direction : directions) {
                    if (rc.canDigDirt(direction) && rc.senseRobotAtLocation(rc.getLocation().add(direction)).getType() != RobotType.HQ) {
                        rc.digDirt(direction);
                        System.out.println("digging to kill "+direction);
                        break;
                    } else {
                        System.out.println("cannot dig to kill "+direction);
                    }
                }
            }

            if (rc.getDirtCarrying() > 0) {
                System.out.println("dumping on enemy hq "+rc.getLocation().directionTo(enemyHQ));
                if (rc.canDepositDirt(rc.getLocation().directionTo(enemyHQ))) {
                    rc.depositDirt(rc.getLocation().directionTo(enemyHQ));
                }
            }
        }

        else if (rc.getLocation().isWithinDistanceSquared(enemyHQ, 8)) {
            if (rc.getDirtCarrying() == 0) {
                Direction direction = rc.getLocation().directionTo(enemyHQ);
                Direction[] dig;
                switch (direction) {
                    case NORTH: dig = FulfillmentCenterController.n; break;
                    case NORTHWEST: dig = FulfillmentCenterController.nw; break;
                    case WEST: dig = FulfillmentCenterController.w; break;
                    case SOUTHWEST: dig = FulfillmentCenterController.sw; break;
                    case SOUTH: dig = FulfillmentCenterController.s; break;
                    case SOUTHEAST: dig = FulfillmentCenterController.se; break;
                    case EAST: dig = FulfillmentCenterController.e; break;
                    default: dig = FulfillmentCenterController.ne; break;
                }
                for (Direction dir : dig) {
                    if (rc.canDigDirt(dir)) {
                        rc.digDirt(dir);
                    }
                }
            }

            if (rc.getDirtCarrying() > 0) {
                if (rc.canDepositDirt(Direction.CENTER)) {
                    rc.depositDirt(Direction.CENTER);
                }
            }
        }

        else {
            tryMove(movementSolver.directionToGoal(enemyHQ));
        }
    }

    public void execKillUnits() throws GameActionException {
        // Prioritize killing in this order
        MapLocation adjacentPos = null;
        MapLocation netGunPos = null;
        MapLocation designSchoolPos = null;
        MapLocation fulfillmentCenterPos = null;

        boolean foundEnemy = false;
        for (RobotInfo robot : rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rc.getTeam().opponent())) {
            if (robot.type == RobotType.NET_GUN) netGunPos = robot.location;
            else if (robot.type == RobotType.DESIGN_SCHOOL) designSchoolPos = robot.location;
            else if (robot.type == RobotType.FULFILLMENT_CENTER) fulfillmentCenterPos = robot.location;

            if (netGunPos != null && isAdjacentTo(netGunPos)) adjacentPos = netGunPos;
            else if (designSchoolPos != null && isAdjacentTo(designSchoolPos)) adjacentPos = designSchoolPos;
            else if (fulfillmentCenterPos != null && isAdjacentTo(fulfillmentCenterPos)) adjacentPos = fulfillmentCenterPos;
            foundEnemy = true;
        }

        if (!foundEnemy) {
            currentState = State.PROTECTHQ;
            execProtectHQ();
        }
        // Kill an adjacent one
        if (adjacentPos != null) {
            if (rc.getDirtCarrying() > 0 && rc.canDepositDirt(rc.getLocation().directionTo(adjacentPos))) {
                rc.depositDirt(rc.getLocation().directionTo(adjacentPos));
            } else {
                for (Direction dir : getDirections()) {
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
                System.out.println(isAdjacentTo(enemy) + " " + enemy + " " + adjacentPos);
                tryMove(movementSolver.directionToGoal(enemy));
            } else {
                if (getChebyshevDistance(rc.getLocation(), allyHQ) <= 2) {
                    tryMove(movementSolver.directionToGoal(rc.getLocation().add(rc.getLocation().directionTo(allyHQ).opposite())));
                } else if (getChebyshevDistance(rc.getLocation(), allyHQ) > 4) {
                    tryMove(movementSolver.directionToGoal(allyHQ));
                } else {
                    // Dig dirt so depositing is fast
                    Direction best = null;
                    int highest = 0;
                    for (Direction dir : getDirections()) {
                        if (dir == rc.getLocation().directionTo(allyHQ)) continue;
                        if (rc.canSenseLocation(rc.getLocation().add(dir)) &&
                                rc.senseElevation(rc.getLocation().add(dir)) > highest &&
                            rc.canDigDirt(dir)) {
                            best = dir;
                            highest = rc.senseElevation(rc.getLocation().add(dir));
                        }
                    }
                    rc.digDirt(best);
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
                for (Direction dir : getDirections()) {
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
        for (Direction d : getDirections()) {
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

    void tryDeposit(Direction d) throws GameActionException {
        if (rc.getDirtCarrying() == 0)
            tryDigRandom();
        else if (rc.canDepositDirt(d))
            rc.depositDirt(d);
    }

    public void newDig() throws GameActionException {
        if (rc.senseRobotAtLocation(allyHQ).getDirtCarrying() > 0 && rc.canDigDirt(rc.getLocation().directionTo(allyHQ))) {
            rc.digDirt(rc.getLocation().directionTo(allyHQ));
            return;
        }
        for (Direction dir : getDirections()) {
            if (getChebyshevDistance(rc.getLocation().add(dir), allyHQ) > 1 && rc.canDigDirt(dir)) {
                rc.digDirt(dir);
                return;
            }
        }
    }

    public void newDeposit() throws GameActionException {
        Direction bestDir = Direction.CENTER;
        for (Direction dir : getDirections()) {
            if (rc.canSenseLocation(rc.getLocation().add(dir)) &&
                    rc.canDepositDirt(dir) &&
                    rc.senseElevation(rc.getLocation().add(dir)) < rc.senseElevation(rc.getLocation().add(bestDir)) &&
                    getChebyshevDistance(rc.getLocation().add(dir), allyHQ)==1) {
                bestDir = dir;
            }
        }
        rc.depositDirt(bestDir);
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
        MapLocation curr = rc.getLocation();
        if (curr.distanceSquaredTo(goal) <= 8 && rc.getRoundNum() > roundToLevelWall) {
            Direction dir = curr.directionTo(goal);
            for (Direction direction : directions) {
                if (curr.add(direction).isAdjacentTo(allyHQ) && !rc.isLocationOccupied(curr.add(direction)))
                    dir = direction;
            }
            if (rc.senseElevation(curr.add(dir)) - rc.senseElevation(curr) > 3) level(dir);
            else tryMove(dir);
        } else {
            Direction dir = movementSolver.directionToGoal(goal);
            if (!dir.equals(Direction.CENTER)) {
                if (tryMove(dir))
                    return;
            }
            dir = dirToGoal(goal);
            System.out.println("Received direction " + dir.toString());
            if (Math.abs(rc.senseElevation(curr) - rc.senseElevation(curr.add(dir))) > 3) {
                if (!level(dir)) {  // need to level dirt
                    // if could not level, try another path
                    recent.set(index, curr.add(dir));
                    index = (index + 1) % recency;
                    return;
                }
            }
            if (!tryMove(dir)) {
                recent.set(index, curr.add(dir));
                index = (index + 1) % recency;
            }
            System.out.println("Tried to move");
        }
    }

    boolean shouldMoveOnWall(MapLocation point) {
        int count = 0;
        for (RobotInfo robot : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (robot.type.equals(RobotType.LANDSCAPER)) {
                MapLocation pos = robot.location;
                if (pos.distanceSquaredTo(point) <= 1)
                    count++;
                if (pos.distanceSquaredTo(allyHQ) > 2 && pos.distanceSquaredTo(allyHQ)<=8
                && point.distanceSquaredTo(pos) > rc.getLocation().distanceSquaredTo(pos)) {
                    return true;
                }
            }
        }
        return count == 0;
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
    final int recency = 8; int index = 0;
    ArrayList<MapLocation> recent = new ArrayList<>(recency);

    public Direction dirToGoal(MapLocation goal) throws GameActionException {
        while (!rc.isReady()) Clock.yield(); // canMove considers cooldown time
        if (recent.size()==0)
            for (int i=0; i<recency; ++i) recent.add(new MapLocation(-1,-1));
        MapLocation from = rc.getLocation();
        // while obstacle ahead, keep looking for new direction
        Direction dir = from.directionTo(goal);
        for (Direction d : getClosestDirections(dir)) {
            if (!isLandscaperObstacle(from, from.add(d))) {
                recent.set(index, from); index = (index + 1)%recency;
                return d;
            }
        }
        // currently stuck
        recent.set(index, from); index = (index + 1)%recency;
        return Direction.CENTER;

    }
    boolean isLandscaperObstacle(MapLocation from, MapLocation to) throws GameActionException {
        // obstacle if is occupied by building, not on map, or previous point
        if (!rc.onTheMap(to)) return true;
        return rc.isLocationOccupied(to) || recent.contains(to);
    }

    Direction[] getClosestDirections(Direction d) {
        return new Direction[]{d, d.rotateLeft(), d.rotateRight(), d.rotateLeft().rotateLeft(),
        d.rotateRight().rotateRight(), d.opposite().rotateRight(), d.opposite().rotateLeft(),
        d.opposite()};
    }
















    // digging water stuff

    public static boolean containsEnoughSoup(int crudeCount) {
        // Made into a function incase we make it more intelligent later
        // e.g look for bigger soup containment's and get to it before enemy
        return crudeCount > 0;
    }

    public SoupCluster searchSurroundingsSoup() throws GameActionException {
        // Check to see if you can detect any soup
        for (int dx = -6; dx <= 6; ++dx) {
            for (int dy = -6; dy <= 6; ++dy) {
                MapLocation sensePos = new MapLocation(rc.getLocation().x + dx, rc.getLocation().y + dy);
                if (!rc.canSenseLocation(sensePos)) continue;

                if (elevationHeight[sensePos.y][sensePos.x] != null) continue;

                // Check robot
                RobotInfo robot = rc.senseRobotAtLocation(sensePos);
                if (robot != null && (robot.type == RobotType.REFINERY)) {
                    buildMap[sensePos.y][sensePos.x] = robot.type.ordinal();
                }

                // Check elevation
                if (!rc.canSenseLocation(sensePos)) continue;
                elevationHeight[sensePos.y][sensePos.x] = rc.senseElevation(sensePos);

                // Check water
                if (!rc.canSenseLocation(sensePos)) continue;
                containsWater[sensePos.y][sensePos.x] = rc.senseFlooding(sensePos);

                // Check soup
                if (containsWater[sensePos.y][sensePos.x]) continue; // Ignore flooded soup (for now)
                if (!rc.canSenseLocation(sensePos)) continue;
                int crudeAmount = rc.senseSoup(sensePos);

                if (soupCount[sensePos.y][sensePos.x] != null) {
                    // Update soup value but dont search for a cluster
                    soupCount[sensePos.y][sensePos.x] = crudeAmount;
                    continue;
                }

                soupCount[sensePos.y][sensePos.x] = crudeAmount;

                if (rc.canSenseLocation(sensePos) &&
                        containsEnoughSoup(crudeAmount) &&
                        !searchedForSoupCluster[sensePos.y][sensePos.x]) {
                    SoupCluster foundSoupCluster = determineCluster(sensePos);

                    if (foundSoupCluster == null) break;

                    soupClusters.add(foundSoupCluster);

                    communicationHandler.sendCluster(foundSoupCluster);
                    return foundSoupCluster;
                }
            }
        }
        return null;
    }

    public void searchSurroundingsContinued() throws GameActionException {
        /*
            Like searchSurroundings, but doesnt try to determine which cluster a soup position is from
         */

        for (int dx = -6; dx <= 6; ++dx) {
            for (int dy = -6; dy <= 6; ++dy) {
                MapLocation sensePos = new MapLocation(rc.getLocation().x + dx, rc.getLocation().y + dy);
                if (!rc.canSenseLocation(sensePos)) continue;
                if (!onTheMap(sensePos)) continue;

                if (elevationHeight[sensePos.y][sensePos.x] != null) continue;

                // Check elevation
                elevationHeight[sensePos.y][sensePos.x] = rc.senseElevation(sensePos);

                // Check water
                containsWater[sensePos.y][sensePos.x] = rc.senseFlooding(sensePos);

                // Check robot
                RobotInfo robot = rc.senseRobotAtLocation(sensePos);
                if (robot != null && (robot.type == RobotType.REFINERY)) {
//                    System.out.println("Found refinery " + sensePos + " " + robot.type.ordinal());
                    buildMap[sensePos.y][sensePos.x] = robot.type.ordinal();
//                    System.out.println(buildMap[sensePos.y][sensePos.x]);
                }

                // Check soup (ignore flooded tiles (for now))
                if (containsWater[sensePos.y][sensePos.x]) {
                    soupCount[sensePos.y][sensePos.x] = 0;
                }
                else soupCount[sensePos.y][sensePos.x] = rc.senseSoup(sensePos);
            }
        }
    }

    public SoupCluster determineCluster(MapLocation pos) throws GameActionException {
        /*
            Performs BFS to determine size of cluster
         */

//        System.out.println("Searching for cluster at " + pos.toString());
//        System.out.println("Determining cluster");
        searchSurroundingsContinued();

        if (searchedForSoupCluster[pos.y][pos.x]) return null;

        RingQueue<MapLocation> queue = new RingQueue<>(this.rc.getMapHeight() * this.rc.getMapWidth());
        queue.add(pos);
        searchedForSoupCluster[pos.y][pos.x] = true;

        int crudeSoup = 0;
        int size = 0;
        boolean containsWaterSoup = false;

        int x1 = pos.x;
        int x2 = pos.x;
        int y1 = pos.y;
        int y2 = pos.y;

        // Incase the enemy has already occupied this spot
        MapLocation refineryPos = null;

        while (!queue.isEmpty() && (x2 - x1) * (y2 - y1) <= 50) {
            MapLocation current = queue.poll();

            visited[current.y][current.x] = true;

            x1 = Math.min(x1, current.x);
            x2 = Math.max(x2, current.x);

            y1 = Math.min(y1, current.y);
            y2 = Math.max(y2, current.y);

            // Determine if we already know about this cluster
            // We keep searching instead of returning to mark each cell as checked
            // so we don't do it again
            ++size;

            for (Direction delta : getDirections()) {
                MapLocation neighbour = current.add(delta);
                if (!inRange(neighbour.y, 0, rc.getMapHeight()) || !inRange(neighbour.x, 0, rc.getMapWidth())) continue;
                if (searchedForSoupCluster[neighbour.y][neighbour.x]) continue;

                // If you cant tell whether neighbour has soup or not move closer to it

                while (!rc.isReady()) Clock.yield();

                boolean isPossible = true;
                while (soupCount[neighbour.y][neighbour.x] == null){
                    if (!canReach(neighbour)) {
                        isPossible = false;
                        break;
                    }
                    if (tryMove(movementSolver.directionToGoal(neighbour))) {
                        searchSurroundingsContinued();

                        // Only do nothing if you need to make another move
                        // if (soupCount[neighbour.y][neighbour.x] == null) Clock.yield();
                    }
                }
                if (!isPossible) continue;

                if (Math.abs(elevationHeight[neighbour.y][neighbour.x] - elevationHeight[current.y][current.x]) > 3) continue;

                crudeSoup += (soupCount[neighbour.y][neighbour.x] == null) ? 0 : soupCount[neighbour.y][neighbour.x];
                containsWaterSoup |= (containsWater[neighbour.y][neighbour.x] != null &&
                        containsWater[neighbour.y][neighbour.x]);

                if (soupCount[neighbour.y][neighbour.x] > 0 || (buildMap[neighbour.y][neighbour.x] != null && buildMap[neighbour.y][neighbour.x] == RobotType.REFINERY.ordinal())) {
                    queue.add(neighbour);
                    searchedForSoupCluster[neighbour.y][neighbour.x] = true;
                }
            }
        }

//        System.out.println("Found: " + size);

        // This isn't used (hopefully)
        SoupCluster found = new SoupCluster(x1, y1, x2, y2, size, crudeSoup, 0, 0);

//        System.out.println("Finished finding cluster: " + found.size);

        // Check to see if other miners have already found this cluster
        boolean hasBeenBroadCasted = false;
        updateClusters();
        for (SoupCluster soupCluster : soupClusters) {
            if (found.inside(soupCluster)) hasBeenBroadCasted = true;
        }

//        System.out.println("Finished: ");
        found.draw(this.rc);
        if (hasBeenBroadCasted) return null;
        return found;
    }

    void updateClusters() throws GameActionException {
        for (int i = lastRound; i < rc.getRoundNum(); ++i) {
            for (Transaction tx : rc.getBlock(i)) {
                int[] mess = tx.getMessage();
                if (communicationHandler.identify(mess) == CommunicationHandler.CommunicationType.CLUSTER) {
                    SoupCluster broadcastedSoupCluster = communicationHandler.getCluster(mess);

                    boolean seenBefore = false;
                    for (SoupCluster alreadyFoundSoupCluster : soupClusters) {
                        if (broadcastedSoupCluster.inside(alreadyFoundSoupCluster)) {
                            alreadyFoundSoupCluster.update(broadcastedSoupCluster);
                            seenBefore = true;
                        }
                    }

                    if (!seenBefore) {
                        // broadcastedSoupCluster.draw(this.rc);
                        soupClusters.add(broadcastedSoupCluster);
                        for (int y = broadcastedSoupCluster.y1; y <= broadcastedSoupCluster.y2; ++y) {
                            for (int x = broadcastedSoupCluster.x1; x < broadcastedSoupCluster.x2; ++x) {
                                searchedForSoupCluster[y][x] = true;
                            }
                        }
                    }

                }
            }
        }
        lastRound = rc.getRoundNum(); // Keep track of last round we scanned the block chain
    }


    static final int HIGHER = 5;
    public void execRemoveWater() throws GameActionException {
        MapLocation location = rc.getLocation();

        if (rc.getRoundNum() >= 1200) { // elevation 5 tiles flooded at round 1210 so try to survive alone
            protectSelf();
            return;
        }

        if (currentSoupCluster == null) {
            if (rc.getRoundNum() >= 1200) { // elevation 5 tiles flooded at round 1210 so try to survive alone
                protectSelf();
                return;
            } else {
                movementSolver.windowsRoam();
            }
        } else {


        boolean allWaterAround = rc.senseElevation(location) < HIGHER;
        for (Direction dir : cardinal) {
            if (!rc.senseFlooding(location.add(dir))) {
                allWaterAround = false;
            }
        }

        if (allWaterAround) {
            for (Direction direction : ordinal) {
                while (rc.senseElevation(location.add(direction)) < HIGHER) {
                    if (rc.getRoundNum() >= 1200) break;

                    if (rc.getDirtCarrying() == 0) {
                        for (Direction dir : getDirections()) {
                            MapLocation digHere = location.add(dir);
                            if (rc.canDigDirt(dir)
                                    && (digHere.x + digHere.y) % 2 == 1) {
                                rc.digDirt(dir);
                                System.out.println("dig for ordinal dig");
                            }
                        }
                    } else {
                        if (rc.canDepositDirt(direction)) {
                            rc.depositDirt(direction);
                            System.out.println("dump center dump");
                        }
                    }
                }
            }
            while (rc.senseElevation(location) < HIGHER) {
                if (rc.getRoundNum() >= 1200) break;

                if (rc.getDirtCarrying() == 0) {
                    for (Direction dir : getDirections()) {
                        MapLocation digHere = location.add(dir);
                        if (rc.canDigDirt(dir)
                                && (digHere.x + digHere.y) % 2 == 1) {
                            rc.digDirt(dir);
                            System.out.println("dig for center dig");
                        }
                    }
                } else {
                    if (rc.canDepositDirt(Direction.CENTER)) {
                        rc.depositDirt(Direction.CENTER);
                        System.out.println("dump center dump");
                    }
                }
            }
        }

        if (rc.getRoundNum() >= 1200) { // elevation 5 tiles flooded at round 1210 so try to survive alone
            protectSelf();
            return;
        }


        boolean near = false;
        for (Direction dir : getDirections()) {
            MapLocation water = location.add(dir);
            if (rc.canSenseLocation(water)
                    && rc.senseFlooding(water)
                    && (location.x + location.y) % 2 == 0
                    && location.isWithinDistanceSquared(currentSoupCluster.center,
                    (int) Math.pow(2 * Math.max(currentSoupCluster.height, currentSoupCluster.width), 2))) {
                near = true;
                break;
            }
        }

        if (rc.getRoundNum() >= 1200) { // elevation 5 tiles flooded at round 1210 so try to survive alone
            protectSelf();
            return;
        }

        if (near) {
            if (rc.getDirtCarrying() == 0) {
                for (Direction dir : getDirections()) {
                    MapLocation digHere = location.add(dir);
                    if (rc.canDigDirt(dir)
//                            && !dumped[digHere.x][digHere.y]
                            && (digHere.x + digHere.y) % 2 == 1) {
                        rc.digDirt(dir);
                        System.out.println("dig dig dig");
                        break;
                    }
                }
            } else {
                MapLocation dumpHere;
                for (Direction dir : getDirections()) {
                    dumpHere = location.add(dir);
                    if (rc.canDepositDirt(dir)
                            && (dumpHere.x + dumpHere.y) % 2 == 0
                            && rc.canSenseLocation(dumpHere)
                            && rc.senseFlooding(dumpHere)) {

                        rc.depositDirt(dir);
                        System.out.println("dump dump dump");
//                        dumped[location.x][location.y] = true;
                        break;
                    }
                }
            }
        } else {
            System.out.println("next");
            tryMove(movementSolver.directionToGoal(currentSoupCluster.center));
        }

        boolean empty = true;
        for (Direction direction : ordinal) {
            if (rc.senseFlooding(location.add(direction))) {
                empty = false;
                break;
            }
        }
        if (empty) {
//            while (upup < 8) {
//                if (rc.getDirtCarrying() == 0) {
//                    for (Direction dir : getDirections()) {
//                        MapLocation digHere = location.add(dir);
//                        if (rc.canDigDirt(dir)
////                            && !dumped[digHere.x][digHere.y]
//                                && (digHere.x + digHere.y) % 2 == 1) {
//                            rc.digDirt(dir);
//                            System.out.println("dig for center dig");
//                        }
//                    }
//                } else {
//                    if (rc.canDepositDirt(Direction.CENTER)) {
//                        rc.depositDirt(Direction.CENTER);
//                        System.out.println("dump center dump");
//                    }
//                    upup++; // avoid stack overflow
//                }
//            }
//            upup = 0;
            tryMove(movementSolver.directionToGoal(currentSoupCluster.center));
        }

        // TODO decide when digging is done
//        int flood = 0;
//        int dry = 0;
//        for (int dx = -6; dx <= 6; ++dx) {
//            for (int dy = -6; dy <= 6; ++dy) {
//                MapLocation sensePos = new MapLocation(rc.getLocation().x + dx, rc.getLocation().y + dy);
//                if (!rc.canSenseLocation(sensePos)) continue;
//                if (!onTheMap(sensePos)) continue;
//
//                if (rc.senseFlooding(sensePos)) {
//                    flood++;
//                } else {
//                    dry++;
//                }
//            }
//        }
//
//        if (dry >= flood || flood - dry >= 5) {
//            currentState = State.ATTACK;
//        }
        }
    }

//    public void execRemoveWaterOneSquare() throws GameActionException {
//        if (dig) {
//            if (rc.canDigDirt(Direction.CENTER)) {
//                rc.digDirt(Direction.CENTER);
//            }
//        }
//        dig = ! dig;
//
//        if (!rc.getLocation().isAdjacentTo(currentSoupSquare)) {
//            tryMove(movementSolver.directionToGoal(currentSoupSquare));
//        }
//
//        Direction depositHere;
//        if ((currentSoupSquare.x + currentSoupSquare.y) % 2 == 0) {
//            depositHere = rc.getLocation().directionTo(currentSoupSquare);
//        }
//
//
//
//
//        while (rc.senseFlooding(currentSoupSquare.add(depositHere))) {
//            execGreedyFill(depositHere);
//        }
//
//    }

    public void protectSelf() throws GameActionException {
        MapLocation location = rc.getLocation();
        if (rc.getDirtCarrying() == 0) {
            for (Direction dir : getDirections()) {
                MapLocation digHere = location.add(dir);
                if (rc.canDigDirt(dir)
                        && (digHere.x + digHere.y) % 2 == 1) {
                    rc.digDirt(dir);
                    System.out.println("dig dig dig");
                    break;
                }
            }
        } else {
            if (rc.canDepositDirt(Direction.CENTER)) {
                rc.depositDirt(Direction.CENTER);
                System.out.println("dump dump dump");
            }
        }
    }

    public static boolean inRange(int a, int lo, int hi) {
        return (lo <= a && a < hi);
    }

    public boolean canReach(MapLocation pos) throws GameActionException {
        int diam = PlayerConstants.SEARCH_DIAMETER;

        // Just checks to see if there is a path to any block in that direction just using
        reachQueue.clear();

        boolean[][] visited = new boolean[diam][diam];

        reachQueue.add(rc.getLocation());
        visited[diam/2 + 1][diam/2 + 1] = true;

        int targetDistance = rc.getLocation().distanceSquaredTo(pos);

        while (!reachQueue.isEmpty()) {
            MapLocation node = reachQueue.poll();

            if (pos.distanceSquaredTo(node) < targetDistance) return true;

            for (Direction dir : getDirections()) {
                MapLocation nnode = node.add(dir);
                if (!rc.onTheMap(nnode)) continue;

                int dx = nnode.x - (rc.getLocation().x - diam/2);
                int dy = nnode.y - (rc.getLocation().y - diam/2);
                if (dx < 0 || dx >= diam || dy < 0 || dy >= diam) continue;

                if (visited[dy][dx]) continue;
                if (containsWater[nnode.y][nnode.x] == null) {
                    // if we haven't searched the square assume we can get there
                    visited[dy][dx] = true;
                    reachQueue.add(nnode);
                } else {
                    if (containsWater[nnode.y][nnode.x]) continue;
                    if (elevationHeight[nnode.y][nnode.x] == null) continue;
                    if (Math.abs(elevationHeight[nnode.y][nnode.x] - elevationHeight[node.y][node.x]) > 3) continue;
                    visited[dy][dx] = true;
                    reachQueue.add(nnode);
                }

            }
        }
        return false;
    }


    public void getHitchHike(MapLocation pos, MapLocation goal) throws GameActionException {
        HitchHike req = new HitchHike(pos, goal, rc.getID());
        MapLocation oldPos = rc.getLocation();
        while (true) {
            if (communicationHandler.sendHitchHikeRequest(req))
                break;
        }
        Clock.yield();

        boolean confirmed = false;
        boolean hasBeenPickedUp = false;
        int lastRound = rc.getRoundNum() - 1;
        // Wait for ACK
        for (int i = 0; i < 64 / GRID_BLOCK_SIZE + 10; ++i) {
            avoidDrone();
            if ((rc.senseElevation(rc.getLocation()) < GameConstants.getWaterLevel(rc.getRoundNum() + 1)) &&
                    isAdjacentToWater(rc.getLocation())) {
                avoidWater();
            }

            if (rc.getRoundNum() - 1 != lastRound) {
                // we have been picked up early
                hasBeenPickedUp = false;
            }
            for (Transaction tx : rc.getBlock(rc.getRoundNum() - 1)) {
                int[] mess = tx.getMessage();
                if (communicationHandler.identify(mess) == CommunicationHandler.CommunicationType.HITCHHIKE_ACK) {
                    HitchHike ack = communicationHandler.getHitchHikeAck(mess);
//                    System.out.println("Found ack: " + ack.pos + " " + ack.goal + " " + pos + " " + goal);
                    if (ack.pos.equals(pos) && ack.goal.equals(goal)) {
                        confirmed = true;
                    }
                }
            }
            lastRound = rc.getRoundNum();
            Clock.yield();
        }
        if (!confirmed) {
            System.out.println("Didnt get ACK");
            return;
        }
        for (int i = 0; i < 80 && !hasBeenPickedUp; ++i) {
            System.out.println(rc.getRoundNum() + " " +  lastRound);
            if (rc.getRoundNum() - 1 == lastRound) {
                lastRound = rc.getRoundNum();

                avoidDrone();
                if ((rc.senseElevation(rc.getLocation()) < GameConstants.getWaterLevel(rc.getRoundNum() + 1)) &&
                        isAdjacentToWater(rc.getLocation())) {
                    avoidWater();
                }

                Clock.yield();
            } else {
                hasBeenPickedUp = true;
            }
        }

        if (!hasBeenPickedUp) return;
    }

    int lastPos = 1;
    public void readBlocks() throws GameActionException {
        while (lastPos < rc.getRoundNum() && Clock.getBytecodesLeft() > 500) {
            for (Transaction t : rc.getBlock(lastPos)) {
                int[] message = t.getMessage();
                switch (communicationHandler.identify(message)) {
                    case LANDSCAPERS_ON_WALL: processLandscapersOnWall(message); break;
                    case ALLYHQ: processAllyHQLoc(message); break;
                    case ENEMYHQ: processEnemyHQLoc(message); break;
                    case CLEAR_FLOOD: processClearFlood(message); break;
                }
            }
            ++lastPos;
        }
    }

    int landscapersOnWall = 0;
    public void processLandscapersOnWall(int[] message) throws GameActionException {
        communicationHandler.decode(message);
        landscapersOnWall = message[1];
    }

    public void processAllyHQLoc(int[] message) throws GameActionException {
        communicationHandler.decode(message);
        allyHQ = new MapLocation(message[2], message[3]);
    }

    public void processEnemyHQLoc(int[] message) throws GameActionException {
        communicationHandler.decode(message);
        enemyHQ = new MapLocation(message[2], message[3]);
    }

    public void processClearFlood(int[] message) throws GameActionException {
        SoupCluster soupCluster = new SoupCluster(message[1], message[2], message[3], message[4]);


        currentSoupCluster = soupCluster;

        if (currentState != State.PROTECTHQ) currentState = State.REMOVE_WATER;
    }
}
