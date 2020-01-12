package originalturtle.Controllers;

import battlecode.common.*;
import com.sun.org.apache.bcel.internal.generic.RETURN;
import originalturtle.*;

import java.util.HashSet;
import java.util.LinkedList;

public class MinerController extends Controller {
    /*
        Current Miner strategy
        - First few miners search for individual soup clusters
            - Slowly turn into miners (that actually mine)

     */
    MovementSolver movementSolver;

    public enum State {
        SEARCH,        // Explores randomly looking for soup
        SEARCHURGENT,  // Goes to area where it knows soup already is
        MINE,          // Mines soup in range
        DEPOSIT,
        SCOUT,
        BUILDER
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

    State currentState = State.SEARCH;
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
    Boolean[][] containsWater = null;
    Integer[][] elevationHeight = null;

    boolean[][] searchedForSoupCluster = null; // Have we already checked if this node should be in a soup cluster

    SoupCluster currentSoupCluster; // Soup cluster the miner goes to
    MapLocation currentSoupSquare; // Soup square the miner mines

    LinkedList<SoupCluster> soupClusters = new LinkedList<>();

    int born;
    int lastRound = 1;

    public MinerController(RobotController rc) {
        this.rc = rc;
        int round = rc.getRoundNum();
        this.born = round;
        this.movementSolver = new MovementSolver(this.rc);
        this.communicationHandler = new CommunicationHandler(this.rc);

        System.out.println("I got built on round " + this.rc.getRoundNum());
        bias = (int) (Math.random() * BIAS_TYPES);

        for (RobotInfo robotInfo : rc.senseNearbyRobots()) {
            if (robotInfo.getType() == RobotType.HQ) allyHQ = robotInfo.location;
        }

        soupCount = new Integer[rc.getMapHeight()][rc.getMapWidth()];
        containsWater = new Boolean[rc.getMapHeight()][rc.getMapWidth()];
        elevationHeight = new Integer[rc.getMapHeight()][rc.getMapWidth()];
        searchedForSoupCluster = new boolean[rc.getMapHeight()][rc.getMapWidth()];

        /*

        if (this.rc.getRoundNum() == 2 || this.rc.getRoundNum() == 3) {
            // This miner is now a scout
            // currentState = State.SCOUT;
            // boardElevations = new Integer[rc.getMapHeight()][rc.getMapWidth()];
        }
        */
        if (round % 3 == 0 && round > 3) { // FIXME: used for testing building buildings
            System.out.println("me BUILDER");
            currentState = State.BUILDER;
            buildType = RobotType.FULFILLMENT_CENTER;
            if (!findBuildLoc()) currentState = State.SEARCH;
        }
    }


    public void run() throws GameActionException {
        System.out.println("I am a " + currentState.toString() + " - " + soupClusters.size());
        if (this.currentSoupCluster != null) this.currentSoupCluster.draw(this.rc);

        updateClusters();

        switch (currentState) {
            case SEARCH: execSearch();             break;
            case MINE: execMine();                 break;
            case DEPOSIT: execDeposit();           break;
            case SEARCHURGENT: execSearchUrgent(); break;
            case BUILDER: execBuilder();           break;
            case SCOUT: execScout();               break;
        }
    }

    boolean containsEnoughSoup(int crudeCount) {
        // Made into a function incase we make it more intelligent later
        // e.g look for bigger soup containment's and get to it before enemy
        return crudeCount > 0;
    }

    public SoupCluster searchSurroundings() throws GameActionException {
        // Check to see if you can detect any soup
        for (int dx = -6; dx <= 6; ++dx) {
            for (int dy = -6; dy <= 6; ++dy) {
                MapLocation sensePos = new MapLocation(rc.getLocation().x + dx, rc.getLocation().y + dy);
                if (!rc.canSenseLocation(sensePos)) continue;
                if (!rc.onTheMap(sensePos)) continue;

                // Check elevation
                elevationHeight[sensePos.y][sensePos.x] = rc.senseElevation(sensePos);

                // Check water
                containsWater[sensePos.y][sensePos.x] = rc.senseFlooding(sensePos);

                // Check soup
                if (containsWater[sensePos.y][sensePos.x]) continue; // Ignore flooded soup (for now)

                int crudeAmount = rc.senseSoup(sensePos);

                if (soupCount[sensePos.y][sensePos.x] != null) {
                    // Update soup value but dont search for a cluster
                    soupCount[sensePos.y][sensePos.x] = crudeAmount;
                    continue;
                }

                soupCount[sensePos.y][sensePos.x] = crudeAmount;

                if (containsEnoughSoup(crudeAmount)) {
                    SoupCluster foundSoupCluster = determineCluster(sensePos);
                    if (foundSoupCluster == null) continue;

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
                if (!rc.onTheMap(sensePos)) continue;

                // Check elevation
                elevationHeight[sensePos.y][sensePos.x] = rc.senseElevation(sensePos);

                // Check water
                containsWater[sensePos.y][sensePos.x] = rc.senseFlooding(sensePos);

                // Check soup (ignore flooded tiles (for now))
                if (containsWater[sensePos.y][sensePos.x]) {
                    System.out.println(sensePos + " contains water");
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

        // Slowly start converting searchers to miners
        // round:  50 - 100% searchers
        // round: 500 -   0% searchers

        // Also mandatory searching time depending on when miner was born
        // round: 0  -> 200 rounds
        // round 200 ->   0 rounds
        if ((rc.getID() % 450) <= (rc.getRoundNum() - 50) && (rc.getRoundNum() - born) > mandatorySearching()) {
            currentState = State.SEARCHURGENT;
            return;
        }

        SoupCluster soupCluster = searchSurroundings();
        if (soupCluster != null) {
            currentState = State.SEARCHURGENT;
            currentSoupCluster = soupCluster;
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
         */

        // If you can no longer carry any soup deposit it
        if (rc.getSoupCarrying() + GameConstants.SOUP_MINING_RATE > RobotType.MINER.soupLimit) {
            currentState = State.DEPOSIT;
            return;
        }

        while (currentSoupSquare == null) {
            movementSolver.restart();

            MapLocation randomSpot;

            do {
                randomSpot = new MapLocation(
                    random.nextInt() % (currentSoupCluster.width) + currentSoupCluster.x1,
                    random.nextInt() % (currentSoupCluster.height) + currentSoupCluster.y1);
            }
            while (containsWater[randomSpot.y][randomSpot.x]);

            while (currentSoupSquare == null && !rc.getLocation().equals(randomSpot)) {
                for (Direction dir : Direction.allDirections()) {
                    MapLocation neighbour = rc.getLocation().add(dir);
                    if (soupCount[neighbour.y][neighbour.x] == null) searchSurroundingsContinued();
                    if (soupCount[neighbour.y][neighbour.x] > 0) {
                        currentSoupSquare = neighbour;
                        break;
                    }
                }
                if (currentSoupSquare != null) break;

                System.out.println("I am a miner looking for soup");
                if (tryMove(movementSolver.directionToGoal(randomSpot))) Clock.yield();
            }
        }

        movementSolver.restart();
        while (getDistanceSquared(rc.getLocation(), currentSoupSquare) > 1) {
            System.out.println("I am a miner going to soup I have found");
            if (tryMove(movementSolver.directionToGoal(currentSoupSquare))) Clock.yield();
        }

        if (rc.senseSoup(currentSoupSquare) > 0) {
            if (!rc.isReady()) Clock.yield();

            while (rc.canMineSoup(rc.getLocation().directionTo(currentSoupSquare))) {
                System.out.println("I am mining soup");
                rc.mineSoup(rc.getLocation().directionTo(currentSoupSquare));
                Clock.yield();
            }
        } else {
            soupCount[currentSoupSquare.y][currentSoupSquare.x] = 0;
            currentSoupSquare = null;
        }
    }

    public void execDeposit() throws GameActionException {
        if (currentSoupCluster.refinery.equals(this.allyHQ)) {
            if (rc.getTeamSoup() > PlayerConstants.REFINERY_BUILD_THRESHOLD &&
                currentSoupCluster.size > PlayerConstants.REFINERY_BUILD_CLUSTER_SIZE) {
                // build refinery

                MapLocation refineryPos = null;

                // While loop as there may be robots in the way
                while (refineryPos == null) {
                    System.out.println("I am looking for a place to build a refinery");
                    for (Direction dir : Direction.allDirections()) {
                        if (tryBuild(RobotType.REFINERY, dir)) {
                            refineryPos = rc.getLocation().add(dir);
                            break;
                        }
                    }
                    Clock.yield();
                }

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
            int totalSoupSquares = 0;
            for (SoupCluster soupCluster : soupClusters) {
                totalSoupSquares += soupCluster.size;
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
        }

        while (getDistanceSquared(rc.getLocation(), currentSoupCluster.closest(rc.getLocation())) > 1) {
            SoupCluster soupCluster = searchSurroundings();
            if (soupCluster != null) {
                currentSoupCluster = soupCluster;
            }
            tryMove(movementSolver.directionToGoal(currentSoupCluster.closest(rc.getLocation())));
            Clock.yield();
        }

        currentState = State.MINE;
    }

    public SoupCluster determineCluster(MapLocation pos) throws GameActionException {
        /*
            Performs BFS to determine size of cluster
         */

        System.out.println("Searching for cluster at " + pos.toString());
        if (searchedForSoupCluster[pos.y][pos.x]) return null;

        RingQueue<MapLocation> queue = new RingQueue<>(this.rc.getMapHeight() * this.rc.getMapWidth());
        queue.add(pos);
        searchedForSoupCluster[pos.y][pos.x] = true;

        int size = 0;

        int x1 = pos.x;
        int x2 = pos.x;
        int y1 = pos.y;
        int y2 = pos.y;

        while (!queue.isEmpty()) {
            MapLocation current = queue.poll();

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
                if (neighbour == current) continue;
                if (!inRange(neighbour.y, 0, rc.getMapHeight()) || !inRange(neighbour.x, 0, rc.getMapWidth())) continue;
                if (searchedForSoupCluster[neighbour.y][neighbour.x]) continue;

                // If you cant tell whether neighbour has soup or not move closer to it

                while (!rc.isReady()) Clock.yield();

                while (soupCount[neighbour.y][neighbour.x] == null){

                    if (tryMove(movementSolver.directionToGoal(neighbour))) {
                        searchSurroundingsContinued();

                        // Only do nothing if you need to make another move
                        if (soupCount[neighbour.y][neighbour.x] == null) Clock.yield();
                    }
                }

                if (soupCount[neighbour.y][neighbour.x] > 0) {
                    queue.add(neighbour);
                    searchedForSoupCluster[neighbour.y][neighbour.x] = true;
                }
            }
        }
        System.out.println("Finished finding cluster");

        SoupCluster found = new SoupCluster(x1, y1, x2, y2, size, allyHQ);

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
                if (communicationHandler.identify(mess, i) == CommunicationHandler.CommunicationType.CLUSTER) {
                    SoupCluster broadcastedSoupCluster = communicationHandler.getCluster(mess);

                    boolean seenBefore = false;
                    for (SoupCluster alreadyFoundSoupCluster : soupClusters) {
                        if (broadcastedSoupCluster.inside(alreadyFoundSoupCluster)) {
                            alreadyFoundSoupCluster.update(broadcastedSoupCluster);
                            seenBefore = true;
                        }
                    }

                    if (!seenBefore) {
                        System.out.println("ADDED: " + broadcastedSoupCluster.toString());
                        // broadcastedSoupCluster.draw(this.rc);
                        soupClusters.add(broadcastedSoupCluster);
                    }

                }
            }
        }
        lastRound = rc.getRoundNum(); // Keep track of last round we scanned the block chain
    }

    public void execBuilder() throws GameActionException {
        if (isAdjacentTo(buildLoc)) {
            System.out.println("trying to build");
            switch (buildType) {
                case REFINERY: tryMultiBuild(RobotType.REFINERY); break;
                case VAPORATOR: tryMultiBuild(RobotType.VAPORATOR); break;
                case DESIGN_SCHOOL: tryMultiBuild(RobotType.DESIGN_SCHOOL); break;
                case FULFILLMENT_CENTER: tryMultiBuild(RobotType.FULFILLMENT_CENTER); break;
                case NET_GUN: tryMultiBuild(RobotType.NET_GUN); break;
            }
        } else {
            tryMove(movementSolver.directionToGoal(buildLoc));
        }
    }

    public void execScout() throws GameActionException {
        searchSurroundings();

        // Currently just keep walking until you have found the enemy HQ or you cant anymore
        for (RobotInfo robotInfo : rc.senseNearbyRobots()) {
            if (robotInfo.team == rc.getTeam().opponent() && robotInfo.type == RobotType.HQ) {
                System.out.println("YIPEEE - Found HQ");
                currentState = State.SEARCH;
                Clock.yield();
            }
        }
        if (rc.isReady() && !tryMove(allyHQ.directionTo(this.rc.getLocation()))) {
            currentState = State.SEARCH;
        }
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

    boolean findBuildLoc() {
        int x = rc.getLocation().x;
        int y = rc.getLocation().y;
        for (int j = 5; j >= 3; j--) {
            for (int i = 0; i < BUILD_LOCS; i++) {
                MapLocation loc = new MapLocation(x + BUILD_DX[i] * j, y + BUILD_DY[i] * j);
                try {
                    if (!rc.senseFlooding(loc) && rc.senseRobotAtLocation(loc) == null) {
                        buildLoc = loc;
                        return true;
                    }
                } catch (GameActionException e) {
//                    System.out.println("Cannot sense or build here");
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
