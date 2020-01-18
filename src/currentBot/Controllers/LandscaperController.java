package currentBot.Controllers;

import battlecode.common.*;
import com.sun.org.apache.bcel.internal.generic.LAND;
import currentBot.*;

import java.security.AllPermission;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Map;

public class LandscaperController extends Controller {

    public enum State {
        PROTECTHQ,  // builds a wall of specified height around HQ
        //PROTECTSOUP,  // builds wall around a soup cluster
        DESTROY,  // piles dirt on top of enemy building
        DEFEND,
        ATTACK,
        ROAM,
        REMOVE_WATER,
        KILLUNITS // Kills units around HQ
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
        getInfo(rc);
        try {
            communicationHandler.receiveLandscapeRole();
        } catch (GameActionException e) {
            e.printStackTrace();
        }

        if (Math.random() > 0.5) currentState = State.PROTECTHQ;
        else currentState = State.KILLUNITS;

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

        if (currentSoupCluster == null && currentState == State.REMOVE_WATER) {
            communicationHandler.receiveClearSoupFlood();
        }


        // System.out.println("I am a " + currentState.toString());
        switch (currentState) {
            case PROTECTHQ:     execProtectHQ();    break;
            //case PROTECTSOUP:   execProtectSoup();  break;
            case DESTROY:       execDestroy();      break;
            case REMOVE_WATER: execRemoveWater(); break;
            case KILLUNITS: execKillUnits();      break;
//            case ROAM: movementSolver.windowsRoam(); break;
        }
    }

    boolean startedWalling = false;
    public void execProtectHQ() throws GameActionException {
        if (allyHQ == null)
            allyHQ = communicationHandler.receiveAllyHQLoc();
        if (!rc.getLocation().isAdjacentTo(allyHQ)) {
            tryMove(movementSolver.directionToGoal(allyHQ));
        }

        if (!startedWalling) {
            boolean goodToWall = true;
            for (Direction direction : directions) {
                RobotInfo robotInfo = rc.senseRobotAtLocation(allyHQ.add(direction));
                if (robotInfo == null || robotInfo.getTeam() != rc.getTeam() || robotInfo.getType() != RobotType.LANDSCAPER) {
                    goodToWall = false;
                    break;
                }
            }
            if (goodToWall) {
                startedWalling = true;
                bigBigWall();
            }
        } else {
            bigBigWall();
        }
    }

    public void bigBigWall() throws GameActionException {
        if (rc.getDirtCarrying() == 0) {
            for (Direction direction : directions) {
                MapLocation digHere = rc.getLocation().add(direction);
                if (!digHere.isAdjacentTo(allyHQ) && !digHere.equals(allyHQ) && rc.canDigDirt(direction)) {
                    rc.digDirt(direction);
                    break;
                }
            }
        } else {
            rc.depositDirt(Direction.CENTER);
        }
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

            if (netGunPos != null && isAdjacentTo(netGunPos)) adjacentPos = netGunPos;
            else if (designSchoolPos != null && isAdjacentTo(designSchoolPos)) adjacentPos = netGunPos;
            else if (fulfillmentCenterPos != null && isAdjacentTo(fulfillmentCenterPos)) adjacentPos = netGunPos;

        }



        // Kill an adjacent one
        if (adjacentPos != null) {
            System.out.println("adjacent enemy");
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
                System.out.println(isAdjacentTo(enemy) + " " + enemy + " " + adjacentPos);
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

            for (Direction delta : Direction.allDirections()) {
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

        SoupCluster found = new SoupCluster(x1, y1, x2, y2, size, crudeSoup, containsWaterSoup);

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
        if (currentSoupCluster == null) {
            movementSolver.windowsRoam();
        } else {

        MapLocation location = rc.getLocation();

        boolean allWaterAround = rc.senseElevation(location) < HIGHER;
        for (Direction dir : cardinal) {
            if (!rc.senseFlooding(location.add(dir))) {
                allWaterAround = false;
            }
        }

        if (allWaterAround) {
            while (rc.senseElevation(location) < HIGHER) {
                if (rc.getDirtCarrying() == 0) {
                    for (Direction dir : Direction.allDirections()) {
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



        boolean near = false;
        for (Direction dir : Direction.allDirections()) {
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

        if (near) {
            if (rc.getDirtCarrying() == 0) {
                for (Direction dir : Direction.allDirections()) {
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
                for (Direction dir : Direction.allDirections()) {
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
//                    for (Direction dir : Direction.allDirections()) {
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

            for (Direction dir : Direction.allDirections()) {
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

}
