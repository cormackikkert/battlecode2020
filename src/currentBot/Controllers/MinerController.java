package currentBot.Controllers;

import battlecode.common.*;
import currentBot.*;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Stack;

public class MinerController extends Controller {
    /*
        Current Miner strategy
        - Have two bots that perform BFS in one half of the map
        - The bots broadcast soup cluster locations
        - Other bots get assigned a soup location to mine from
        - After mining they go to other soup locations

     */
    public enum State {
        SEARCH,        // Explores randomly looking for soup
        SEARCHURGENT,  // Goes to area where it knows soup already is
        MINE,          // Mines soup in range
        DEPOSIT,
        SCOUT,
        BUILDER,
        SPECIALOPSBUILDER, // builds the fulfillment center which builds the drone scouts
        RUSHBOT, // Rushes to enemy HQ and builds a design school
        EXPLORE  // Uses DFS to find soup locations (DFS > BFS)
    }

    CommunicationHandler communicationHandler;

    final int BIAS_TYPES = 16;
    int[] BIAS_DX = {0,1,2,3,4,3,2,1,0,-1,-2,-3,-4,-3,-2,-1};
    int[] BIAS_DY = {4,3,2,1,0,-1,-2,-3,-4,-3,-2,-1,0,1,2,3};

    /**
     * Trying to build in one of 8 locations (T) around spawn point (S): (can be improved, used for testing currently)
     * T T T
     * T S T
     * T T T
     */
    final int BUILD_LOCS = 8;
    int[] BUILD_DX = {-1,0,1,-1,1,-1,0,1};
    int[] BUILD_DY = {1,1,1,0,0,-1,-1,-1};

    int bias; // Which bias the robot has
    MapLocation BIAS_TARGET; // which square the robot is targeting
    MapLocation searchTarget;

    State currentState = State.SEARCHURGENT;
    int velx = 0;
    int vely = 0;

    SimpleRandom random = new SimpleRandom();
    // Uncommented because bad maps can screw over the idea of using elevations for symmetry
    // (Instead look for HQ)
    // Integer[][] boardElevations = null; // Used for scouts (scouts should be out before landscaping happens)

    RobotType buildType = null;
    MapLocation buildLoc;

    // Integer (instead of int) so that we can use null as unsearched

    // Arrays that are filled as each miner searches the map
    Integer[][] soupCount = null;
    Integer[][] elevationHeight = null;
    Integer[][] buildMap = null; // Stores what buildings have been built and where

    boolean[][] searchedForSoupCluster = null; // Have we already checked if this node should be in a soup cluster

    SoupCluster currentSoupCluster; // Soup cluster the miner goes to
    MapLocation currentSoupSquare; // Soup square the miner mines

    LinkedList<SoupCluster> soupClusters = new LinkedList<>();

    int born;
    int lastRound = 1;

    int compressedWidth;
    int compressedHeight;
    int BLOCK_SIZE = PlayerConstants.GRID_BLOCK_SIZE;

    Symmetry searchSymmetry = null; // Symmetry that explore bot sticks too

    public MinerController(RobotController rc) {
        this.rc = rc;
        System.out.println("Soup is at " + rc.getTeamSoup());
        int round = rc.getRoundNum();
        this.born = round;
        this.movementSolver = new MovementSolver(this.rc, this);
        this.communicationHandler = new CommunicationHandler(this.rc);
        queue = new RingQueue<>(rc.getMapHeight() * rc.getMapWidth());

//        System.out.println("I got built on round " + this.rc.getRoundNum());
        bias = (int) (Math.random() * BIAS_TYPES);

        // Check if a fulfillment center hasn't been built already
        boolean foundHQ = false;
        boolean builtFC = false;
        for (RobotInfo robotInfo : rc.senseNearbyRobots()) {
            if (robotInfo.getType() == RobotType.HQ) {foundHQ = true; allyHQ = robotInfo.location;}
            if (robotInfo.getType() == RobotType.FULFILLMENT_CENTER) {builtFC = true;}
        }


        System.out.println("Should I build?");
        if (!builtFC && foundHQ &&
                rc.getTeamSoup() > PlayerConstants.buildSoupRequirements(RobotType.FULFILLMENT_CENTER)) {
            System.out.println("YES");
            currentState = State.BUILDER;
            buildType = RobotType.FULFILLMENT_CENTER;
        }

        soupCount = new Integer[rc.getMapHeight()][rc.getMapWidth()];
        containsWater = new Boolean[rc.getMapHeight()][rc.getMapWidth()];
        elevationHeight = new Integer[rc.getMapHeight()][rc.getMapWidth()];
        searchedForSoupCluster = new boolean[rc.getMapHeight()][rc.getMapWidth()];
        buildMap = new Integer[rc.getMapHeight()][rc.getMapWidth()];
        visited = new boolean[rc.getMapHeight()][rc.getMapWidth()];

        compressedHeight = rc.getMapHeight() / PlayerConstants.GRID_BLOCK_SIZE + ((rc.getMapHeight() % PlayerConstants.GRID_BLOCK_SIZE == 0) ? 0 : 1);
        compressedWidth = rc.getMapWidth() / PlayerConstants.GRID_BLOCK_SIZE + ((rc.getMapWidth() % PlayerConstants.GRID_BLOCK_SIZE == 0) ? 0 : 1);

        seenBlocks = new boolean[compressedHeight][compressedWidth];

        if (born == 2) {
            searchSymmetry = Symmetry.VERTICAL;
            currentState = State.EXPLORE;
        } else if (born == 3) {
            searchSymmetry = Symmetry.HORIZONTAL;
            currentState = State.EXPLORE;
        }


        try {
            enemyHQ = communicationHandler.receiveEnemyHQLoc();
        } catch (Exception e) {
            e.printStackTrace();
        }

        //System.out.println("Spawned: " + builtFC + " " + foundHQ + " " + PlayerConstants.buildSoupRequirements(RobotType.FULFILLMENT_CENTER));// + " " + (rc.getTeamSoup() > PlayerConstants.buildSoupRequirements(RobotType.FULFILLMENT_CENTER)));


    }


    public void run() throws GameActionException {
        if (this.currentSoupCluster != null) this.currentSoupCluster.draw(this.rc);


        updateClusters();

        switch (currentState) {
            case SEARCH: execSearch();             break;
            case MINE: execMine();                 break;
            case DEPOSIT: execDeposit();           break;
            case SEARCHURGENT: execSearchUrgent(); break;
            case BUILDER: execBuilder();           break;
            case SCOUT: execScout();               break;
            case RUSHBOT: execRush();              break;
            case EXPLORE: execExplore();           break;
        }
    }

    boolean containsEnoughSoup(int crudeCount) {
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

    private int mandatorySearching() {
        return Math.max(0, (200 - born) * (rc.getMapHeight() * rc.getMapWidth()) / (32 * 32));
    }

    public void execSearch() throws GameActionException {
        currentSoupSquare = null;

        // Slowly start converting searchers to miners
        // round:  50 - 100% searchers
        // round: 300 -   0% searchers

        // Also mandatory searching time depending on when miner was born
        // round: 0  -> 200 rounds
        // round 200 ->   0 rounds
        if ((rc.getID() % 250) <= (rc.getRoundNum() - 50) && (rc.getRoundNum() - born) > mandatorySearching()) {
            currentState = State.SEARCHURGENT;
            return;
        }

        SoupCluster soupCluster = searchSurroundingsSoup();
        if (soupCluster != null) {
            currentState = State.SEARCHURGENT;
            currentSoupCluster = soupCluster;
            return;
        }

        updateClusters();
        if (soupClusters != null) {
            currentState = State.SEARCHURGENT;
            return;
        }


        /* Movement approach:
            keep a velocity vector (velx, vely) and move in this direction.
            modify this to point away from other robots and obstructions

            This seems fairly extendable (can also make velocity vector point to target)
            Might incorporate this as default movement thingy
         */

        // Find average vector pointing to all other units
        int avgx = 0;
        int avgy = 0;
        for (RobotInfo robot : rc.senseNearbyRobots()) {
            int dist = getDistanceSquared(robot.location, rc.getLocation());
            avgx += Math.max(5-dist, 0) * (robot.location.x - rc.getLocation().x);
            avgy += Math.max(5-dist, 0) * (robot.location.y - rc.getLocation().y);
        }

        // Move in opposite direction
        velx -= avgx;
        vely -= avgy;

        MapLocation target = new MapLocation(rc.getLocation().x + velx, rc.getLocation().y + vely);

        Direction toMove = movementSolver.directionToGoal(target);

        if (!inRange(target.x, 0, rc.getMapWidth()) || !inRange(target.y, 0, rc.getMapHeight()) || !tryMove(toMove)) {
            // Got stuck, reset velocity
            bias = (int) (Math.random() * 16);
            velx = 0;
            vely = 0;
        }

        if (velx == 0 && vely == 0) {
            velx = BIAS_DX[bias];
            vely = BIAS_DY[bias];
        }



        // Dampening (velocity reduces by 1) deleted might add back later
    }

    public void execMine() throws GameActionException {
        /*
            Assumes a miner is within a soup cluster
            Looks for soup then mines it until inventory is full

            If soup cluster is finished, tell everyone else and go to another one
         */
        updateClusters();
        searchSurroundingsSoup();

        // If you can no longer carry any soup deposit it
        if (rc.getSoupCarrying() + GameConstants.SOUP_MINING_RATE > RobotType.MINER.soupLimit) {
            currentState = State.DEPOSIT;
            return;
        }
        if (currentSoupSquare == null || !currentSoupCluster.contains(currentSoupSquare)) {
            currentSoupSquare = null;

            // Find closest square that doesn't have no soup guaranteed (it may not know)
            // runs in same time as BFS worst case but I have a feeling this is faster (queue overhead)
            int bestDistance = 65 * 65;
            for (int x = currentSoupCluster.x1; x <= currentSoupCluster.x2; ++x) {
                for (int y = currentSoupCluster.y1; y <= currentSoupCluster.y2; ++y) {
                    if (soupCount[y][x] != null && soupCount[y][x] == 0) continue;
                    if (containsWater[y][x] == null) {
                        // Try to reach water
                        for (int i = 0; i < PlayerConstants.MOVES_BY_MINER && containsWater[y][x] == null;) {
                            i += (rc.isReady()) ? 1 : 0;
                            tryMove(movementSolver.directionToGoal(new MapLocation(x, y)));
                            searchSurroundingsContinued();
                        }
                    }
                    if (containsWater[y][x]) continue;
                    int dist = getDistanceSquared(rc.getLocation(), new MapLocation(x, y));
                    if (dist < bestDistance) {
                        currentSoupSquare = new MapLocation(x, y);
                        bestDistance = dist;
                    }
                }
            }
        }

        if (currentSoupSquare == null || currentSoupCluster.size == 0) {
            currentState = State.SEARCHURGENT;

            // Communicate that the soup cluster is finished
            currentSoupCluster.size = 0;
//            System.out.println("This cluster is finished");
            communicationHandler.sendCluster(currentSoupCluster);

            // Reset variables
            currentSoupCluster = null;
            currentSoupSquare = null;

            return;
        }

        movementSolver.restart();
        while (getDistanceSquared(rc.getLocation(), currentSoupSquare) > 1) {
//            System.out.println("I am a miner going to soup I have found " + currentSoupSquare.toString() + " " + currentSoupCluster.toStringPos());
            if (tryMove(movementSolver.directionToGoal(currentSoupSquare))) Clock.yield();
        }

        if (rc.senseSoup(currentSoupSquare) > 0) {
            if (!rc.isReady()) Clock.yield();

            while (rc.canMineSoup(rc.getLocation().directionTo(currentSoupSquare))) {
//                System.out.println("I am mining soup");
                rc.mineSoup(rc.getLocation().directionTo(currentSoupSquare));

                // Might as well do computation now as we have already made our turn
                int start = rc.getRoundNum();

                for (int dx = -6; dx <= 6; ++dx) {
                    for (int dy = -6; dy <= 6; ++dy) {
                        MapLocation tile = new MapLocation(rc.getLocation().x + dx, rc.getLocation().y + dy);
                        if (!rc.canSenseLocation(tile)) continue;
                        try {
                            soupCount[tile.y][tile.x] = rc.senseSoup(tile);
                        } catch (Exception e) {
//                            System.out.println("WtF " + tile + " " + rc.getLocation() + " " + start + " " + rc.getRoundNum());
                        }
                    }
                }
            }
        } else {
            soupCount[currentSoupSquare.y][currentSoupSquare.x] = 0;
            currentSoupSquare = null;
        }
    }

    public void execDeposit() throws GameActionException {
        searchSurroundingsContinued();

        if (currentSoupCluster.refinery.equals(this.allyHQ)) {
            if (rc.getTeamSoup() > PlayerConstants.REFINERY_BUILD_THRESHOLD &&
                currentSoupCluster.size > PlayerConstants.REFINERY_BUILD_CLUSTER_SIZE &&
                currentSoupCluster.contains(rc.getLocation())) {
                // build refinery

                MapLocation refineryPos = null;

                // While loop as there may be robots in the way
                while (refineryPos == null) {
//                    System.out.println("I am looking for a place to build a refinery");
                    for (Direction dir : Direction.allDirections()) {
                        if (tryBuild(RobotType.REFINERY, dir)) {
                            refineryPos = rc.getLocation().add(dir);
                            break;
                        }
                    }
                    Clock.yield();
                }

                // Communicate the fact that a refinery has been built
                currentSoupCluster.refinery = refineryPos;
                communicationHandler.sendCluster(currentSoupCluster);
            }
        }

        if (getDistanceSquared(rc.getLocation(), currentSoupCluster.refinery) <= 1) {
            if (rc.canDepositSoup(rc.getLocation().directionTo(currentSoupCluster.refinery))) {
                rc.depositSoup(rc.getLocation().directionTo(currentSoupCluster.refinery), rc.getSoupCarrying());
                currentState = State.SEARCHURGENT;
            }
        } else {
            tryMove(movementSolver.directionToGoal(currentSoupCluster.refinery));
        }
    }

    public void execSearchUrgent() throws GameActionException {
        // Decide which cluster each miner goes to by using ID's
        // So each cluster has the number of miners proportional to its size

        if (currentSoupCluster == null) {
            currentSoupSquare = null;
            updateClusters();

            int totalSoupSquares = 0;
            for (SoupCluster soupCluster : soupClusters) {
                totalSoupSquares += soupCluster.size;
            }

            if (totalSoupSquares == 0) {
                currentState = State.SEARCH;
                execSearch();
                return;
            }

            int v = this.rc.getID() % totalSoupSquares;
            int behind = 0;
            for (SoupCluster soupCluster : soupClusters) {
                if (v < behind + soupCluster.size) {
                    currentSoupCluster = soupCluster;
                    break;
                }
                behind += soupCluster.size;
            }

            if (currentSoupCluster == null) {
                // Explore time
                System.out.println("YASSS");
            }
        }

        movementSolver.restart();
        while (getDistanceSquared(rc.getLocation(), currentSoupCluster.closest(rc.getLocation())) > 2) {
            /*
                Now as we have dedicated searches we only focus on getting to the soup cluster

            SoupCluster soupCluster = searchSurroundingsSoup();
            if (soupCluster != null) {
                currentSoupCluster = soupCluster;
            }

             */
//            System.out.println("Heading to soup cluster at " + currentSoupCluster.toStringPos());
            tryMove(movementSolver.directionToGoal(currentSoupCluster.closest(rc.getLocation())));
            searchSurroundingsSoup();
            Clock.yield();
        }
//        System.out.println("Arrived");
        currentState = State.MINE;
    }

    public SoupCluster determineCluster(MapLocation pos) throws GameActionException {
        /*
            Performs BFS to determine size of cluster
         */

//        System.out.println("Searching for cluster at " + pos.toString());
        if (searchedForSoupCluster[pos.y][pos.x]) return null;

        RingQueue<MapLocation> queue = new RingQueue<>(this.rc.getMapHeight() * this.rc.getMapWidth());
        queue.add(pos);
        searchedForSoupCluster[pos.y][pos.x] = true;

        int crudeSoup = 0;
        int size = 0;

        int x1 = pos.x;
        int x2 = pos.x;
        int y1 = pos.y;
        int y2 = pos.y;

        // Incase the enemy has already occupied this spot
        MapLocation refineryPos = null;

        while (!queue.isEmpty() && (x2 - x1) * (y2 - y1) <= 50) {
            MapLocation current = queue.poll();
            if (buildMap[current.y][current.x] != null && buildMap[current.y][current.x] == RobotType.REFINERY.ordinal()) {
                refineryPos = current;
            }

            visited[current.y][current.x] = true;

            x1 = Math.min(x1, current.x);
            x2 = Math.max(x2, current.x);

            y1 = Math.min(y1, current.y);
            y2 = Math.max(y2, current.y);

            // Determine if we already know about this cluster
            // We keep searching instead of returning to mark each cell as checked
            // so we don't do it again

            for (Direction delta : Direction.allDirections()) {
                MapLocation neighbour = current.add(delta);
                if (!inRange(neighbour.y, 0, rc.getMapHeight()) || !inRange(neighbour.x, 0, rc.getMapWidth())) continue;
                if (searchedForSoupCluster[neighbour.y][neighbour.x]) continue;

                // If you cant tell whether neighbour has soup or not move closer to it

                while (!rc.isReady()) Clock.yield();

                int usedMoves = 0; // remove infinite loops
                while (soupCount[neighbour.y][neighbour.x] == null){
                    if (usedMoves > 10) {
                        usedMoves = -1; // lazy flag
                        break;
                    }
                    if (tryMove(movementSolver.directionToGoal(neighbour))) {
                        ++usedMoves;
                        searchSurroundingsContinued();

                        // Only do nothing if you need to make another move
                        if (soupCount[neighbour.y][neighbour.x] == null) Clock.yield();
                    }
                }
                if (usedMoves < 0) continue;
                if (Math.abs(elevationHeight[neighbour.y][neighbour.x] - elevationHeight[current.y][current.x]) > 3) continue;

                crudeSoup += (soupCount[neighbour.y][neighbour.x] == null) ? 0 : soupCount[neighbour.y][neighbour.x];


                if (soupCount[neighbour.y][neighbour.x] > 0 || (buildMap[neighbour.y][neighbour.x] != null && buildMap[neighbour.y][neighbour.x] == RobotType.REFINERY.ordinal())) {
                    queue.add(neighbour);
                    searchedForSoupCluster[neighbour.y][neighbour.x] = true;
                }
            }
        }


        SoupCluster found = new SoupCluster(x1, y1, x2, y2, crudeSoup, (refineryPos == null) ? allyHQ : refineryPos);

//        System.out.println("Finished finding cluster: " + found.size);

        // Check to see if other miners have already found this cluster
        boolean hasBeenBroadCasted = false;
        updateClusters();
        for (SoupCluster soupCluster : soupClusters) {
            if (found.inside(soupCluster)) hasBeenBroadCasted = true;
        }

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

    public void execBuilder() throws GameActionException {
        if (buildLoc == null) {
            buildLoc = getNearestBuildTile();
        }

        if (!isAdjacentTo(buildLoc)) {
            tryMove(movementSolver.directionToGoal(buildLoc));
        } else {
            if (rc.getTeamSoup() > PlayerConstants.buildSoupRequirements(this.buildType)) {
                System.out.println("Checking for others");
                if (PlayerConstants.shouldntDuplicate(this.buildType)) {
                    for (RobotInfo robot : rc.senseNearbyRobots()) {
                        if (robot.team == rc.getTeam() && robot.type == this.buildType) {
                            currentState = State.SEARCHURGENT;
                            return;
                        }
                    }
                }
                if (tryBuild(this.buildType, rc.getLocation().directionTo(buildLoc))) {
                    currentState = State.SEARCHURGENT;
                }
            }
        }
    }

    public void execScout() throws GameActionException {
        searchSurroundingsSoup();

        // Currently just keep walking until you have found the enemy HQ or you cant anymore
        for (RobotInfo robotInfo : rc.senseNearbyRobots()) {
            if (robotInfo.team == rc.getTeam().opponent() && robotInfo.type == RobotType.HQ) {
//                System.out.println("YIPEEE - Found HQ");
                currentState = State.SEARCH;
                // FIXME : for transmitting enemy HQ loc
                communicationHandler.sendEnemyHQLoc(robotInfo.location);
                Clock.yield();
            }
        }
        if (rc.isReady() && !tryMove(allyHQ.directionTo(this.rc.getLocation()))) {
//            System.out.println("Stop enemy searching");
            currentState = State.SEARCH;
        }
    }

    public void execRush() throws GameActionException {
        searchSurroundingsSoup();
        // isRush = false; // This function is meant to only execute once

        MapLocation candidateEnemyHQ;
        switch (born) {
            case 2 :
                candidateEnemyHQ = new MapLocation(rc.getMapWidth() - allyHQ.x - 1, allyHQ.y);
                break;
            case 3:
                candidateEnemyHQ = new MapLocation(allyHQ.x, rc.getMapHeight() - allyHQ.y - 1);
                break;
            case 11:
                candidateEnemyHQ = new MapLocation(rc.getMapWidth() - allyHQ.x - 1, rc.getMapHeight() - allyHQ.y - 1);
                break;
            default:
                candidateEnemyHQ = new MapLocation(rc.getMapWidth() - allyHQ.x - 1, rc.getMapHeight() - allyHQ.y - 1);
//                System.out.println("REEE");

        }

        movementSolver.restart();
        while (getDistanceSquared(rc.getLocation(),candidateEnemyHQ) > 8) {
//            System.out.println("I am searching for enemy HQ");
            tryMove(movementSolver.directionToGoal(candidateEnemyHQ));
            Clock.yield();
        }

        boolean found = false;
        for (RobotInfo robotInfo : rc.senseNearbyRobots()) {
            if (robotInfo.team == rc.getTeam().opponent() && robotInfo.type == RobotType.HQ) {
                // Found HQ yahtzee!!
//                System.out.println("Found enemy HQ");
                communicationHandler.sendEnemyHQLoc(robotInfo.location);
                enemyHQ = robotInfo.location;
                found = true;
            }
        }

        if (found) {
            // If we found the enemy HQ build a design school right next to it
            while (rc.getTeamSoup() < RobotType.DESIGN_SCHOOL.cost) {
                Clock.yield();
            }

            // Build design school near enemy HQ
            if (!tryBuild(RobotType.DELIVERY_DRONE, rc.getLocation().directionTo(enemyHQ))) {
                for (Direction dir : Direction.allDirections()) {
                    if (tryBuild(RobotType.DESIGN_SCHOOL, dir)) break;
                }
            }
        }

        currentState = State.SEARCH;
        execSearch();
    }

    public void execExplore() throws GameActionException {
        Stack<MapLocation> stack = new Stack<>();
        LinkedList<MapLocation> visitedBlocks = new LinkedList<>();

        stack.push(rc.getLocation());
        searchSurroundingsSoup();

        while (!stack.isEmpty()) {
            MapLocation node = stack.pop();

            // Broadcast what areas have been searched
            if (!seenBlocks[node.y / BLOCK_SIZE][node.x / BLOCK_SIZE]) {
                visitedBlocks.add(new MapLocation(node.x / BLOCK_SIZE, node.y / BLOCK_SIZE));
                seenBlocks[node.y / BLOCK_SIZE][node.x / BLOCK_SIZE] = true;

                if (visitedBlocks.size() == 12) {
                    communicationHandler.sendMapBlocks(visitedBlocks.toArray(new MapLocation[0]));
                    System.out.println("Sending " + visitedBlocks.size());
                    visitedBlocks = new LinkedList<>();
                }
            }

            if (visited[node.y][node.x]) continue;
            visited[node.y][node.x] = true;

            for (Direction dir : Direction.allDirections()) {
                MapLocation nnode = node.add(dir);
                if (!onTheMap(nnode) || visited[nnode.y][nnode.x]) continue;

                for (int i = 0; i < 10 && elevationHeight[nnode.y][nnode.x] == null; ++i) {
                    tryMove(movementSolver.directionToGoal(nnode));
                    searchSurroundingsSoup();

                    Clock.yield();
                }

                if (elevationHeight[nnode.y][nnode.x] == null) continue;

                if (containsWater[nnode.y][nnode.x]) continue;

                // Don't explore if this node goes out side of the region of symmetry
                if (searchSymmetry == Symmetry.VERTICAL) {
                    if (getDistanceSquared(nnode, allyHQ) > getDistanceSquared(nnode,
                            new MapLocation(rc.getMapWidth() - allyHQ.x - 1, allyHQ.y))) continue;
                } else if (searchSymmetry == Symmetry.HORIZONTAL) {
                    if (getDistanceSquared(nnode, allyHQ) > getDistanceSquared(nnode,
                            new MapLocation(allyHQ.x, rc.getMapHeight() - allyHQ.y - 1))) continue;
                }

                if (Math.abs(elevationHeight[nnode.y][nnode.x] - elevationHeight[node.y][node.x]) <= 3) {
                    stack.push(nnode);
                }
            }
        }
        if (visitedBlocks.size() > 0) {
            communicationHandler.sendMapBlocks(visitedBlocks.toArray(new MapLocation[0]));
        }
        currentState = State.SEARCHURGENT;
    }

    int reduce(int val, int decay) {
        // Reduces magnitude of val by decay
        if (val > 0) return Math.max(0, val - decay);
        if (val < 0) return Math.min(0, val + decay);
        return val;
    }

    boolean inRange(int a, int lo, int hi) {
        return (lo <= a && a < hi);
    }

    boolean findBuildLoc(){
        int range = 5;

        // build scouting building
        if (enemyHQ == null) {
            range = 1;
            RobotInfo[] allies = rc.senseNearbyRobots(8, rc.getTeam());
            for (RobotInfo ally : allies) {
                if (ally.getType() == RobotType.FULFILLMENT_CENTER) {
                    range = 5;
                    break;
                }
            }
        }

        int x = rc.getLocation().x;
        int y = rc.getLocation().y;
        for (int i = 0; i < BUILD_LOCS; i++) {
            MapLocation loc = new MapLocation(x + BUILD_DX[i] * range, y + BUILD_DY[i] * range);
            if (rc.canSenseLocation(loc)) {
                try {
                    if (!rc.senseFlooding(loc) && rc.senseRobotAtLocation(loc) == null) {
                        buildLoc = loc;
                        return true;
                    }
                } catch (GameActionException e) {
                    e.printStackTrace();
                }
            }
        }
        return false;
    }

    void tryMultiBuild(RobotType robotType) throws GameActionException {
        for (Direction dir : directions) {
            if (rc.canBuildRobot(robotType, dir)) {
                rc.buildRobot(robotType, dir);
//                System.out.println("built a "+robotType);
                currentState = State.SEARCH; // FIXME: switch to search for testing purposes, specifically to conserve soup
                break;
            }
        }
    }
}
