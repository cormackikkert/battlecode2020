package originalturtle.Controllers;

import battlecode.common.*;
import originalturtle.CommunicationHandler;
import originalturtle.MovementSolver;
import originalturtle.RingQueue;
import originalturtle.SoupCluster;

import java.util.HashSet;
import java.util.LinkedList;

public class MinerController extends Controller {
    /*
        Current Miner strategy
        - First few miners search for individual soup clusters
            - Slowly turn into miners (that actually mine)

     */
    HashSet<MapLocation> soupSquares = new HashSet<>();
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

    // Mine variables
    MapLocation curSoupSource; // current source used in MINE state

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

    // Uncommented because bad maps can screw over the idea of using elevations for symmetry
    // (Instead look for HQ)
    // Integer[][] boardElevations = null; // Used for scouts (scouts should be out before landscaping happens)

    RobotType buildType = null;
    MapLocation buildLoc;

    // Integer (instead of int) so that we can use null as unsearched
    Integer[][] soupCount = null;
    boolean[][] searchedForSoupCluster = null; // Have we already checked if this node should be in a soup cluster

    LinkedList<SoupCluster> soupClusters = new LinkedList<>();

    int lastRound = 1;

    public MinerController(RobotController rc) {
        this.rc = rc;
        int round = rc.getRoundNum();
        this.movementSolver = new MovementSolver(this.rc);
        this.communicationHandler = new CommunicationHandler(this.rc);

        System.out.println("I got built on round " + this.rc.getRoundNum());
        bias = (int) (Math.random() * BIAS_TYPES);

        for (RobotInfo robotInfo : rc.senseNearbyRobots()) {
            if (robotInfo.getType() == RobotType.HQ) allyHQ = robotInfo.location;
        }

        soupCount = new Integer[rc.getMapHeight()][rc.getMapWidth()];
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

    public SoupCluster searchForSoup() throws GameActionException {
        // Check to see if you can detect any soup
        for (int dx = -6; dx <= 6; ++dx) {
            for (int dy = -6; dy <= 6; ++dy) {
                MapLocation sensePos = new MapLocation(rc.getLocation().x + dx, rc.getLocation().y + dy);
                if (!rc.canSenseLocation(sensePos)) continue;
                if (!rc.onTheMap(sensePos)) continue;
                if (soupCount[sensePos.y][sensePos.x] != null) continue;



                int crudeAmount = rc.senseSoup(sensePos);
                soupCount[sensePos.y][sensePos.x] = crudeAmount;

                if (rc.canSenseLocation(sensePos) && containsEnoughSoup(crudeAmount)) {
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

    public void searchForSoupContinued() throws GameActionException {
        /*
            Like searchForSoup, but doesnt try to determine which cluster a soup position is from
         */
        // Check to see if you can detect any soup
        for (int dx = -6; dx <= 6; ++dx) {
            for (int dy = -6; dy <= 6; ++dy) {
                MapLocation sensePos = new MapLocation(rc.getLocation().x + dx, rc.getLocation().y + dy);
                if (!rc.canSenseLocation(sensePos)) continue;
                if (!rc.onTheMap(sensePos)) continue;
                if (soupCount[sensePos.y][sensePos.x] != null) continue;

                int crudeAmount = rc.senseSoup(sensePos);
                soupCount[sensePos.y][sensePos.x] = crudeAmount;
            }
        }
    }

    public void execSearch() throws GameActionException {

        // Slowly start converting searchers to miners
        /*
        if (rc.getID() % 200 > (Math.max(1, 200 - rc.getRoundNum()))) {
            currentState = State.SEARCHURGENT;
            return;
        }

         */

        SoupCluster soupCluster = searchForSoup();
        if (soupCluster != null) {
            while (true) Clock.yield();
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
            Assumes robot is next to a soup deposit, mines the soup
            Continues mining until soup inventory is full
         */

        // If you can no longer carry any soup deposit it
        if (rc.getSoupCarrying() + GameConstants.SOUP_MINING_RATE > RobotType.MINER.soupLimit) {
            currentState = State.DEPOSIT;
        }


        if (getDistanceSquared(rc.getLocation(), curSoupSource) <= 1 && rc.senseSoup(curSoupSource) > 0) {
            if (!rc.isReady()) Clock.yield();

            while (rc.canMineSoup(rc.getLocation().directionTo(curSoupSource))) {
                rc.mineSoup(rc.getLocation().directionTo(curSoupSource));
                Clock.yield();
            }
        } else {
            if (rc.senseSoup(curSoupSource) == 0) soupSquares.remove(curSoupSource);
            if (soupSquares.size() > 0)
                currentState = State.SEARCHURGENT;
            else
                currentState = State.SEARCH;
        }
    }

    public void execDeposit() throws GameActionException {
        if (getDistanceSquared(rc.getLocation(), allyHQ) <= 1) {
            if (rc.isReady() && rc.getSoupCarrying() > 0) {
                rc.depositSoup(rc.getLocation().directionTo(allyHQ), rc.getSoupCarrying());
                currentState = State.SEARCHURGENT;
            }
        } else {
            tryMove(movementSolver.directionToGoal(allyHQ));
        }
    }

    public void execSearchUrgent() throws GameActionException {
        MapLocation nearestSoupSquare = null;
        int nearestSoupDist = 2*65*65;

        System.out.println("Deciding: ");
        for (MapLocation soupSquare : soupSquares) {
            System.out.println("Dist: " + getDistanceSquared(rc.getLocation(), soupSquare));
            if (getDistanceSquared(rc.getLocation(), soupSquare) < nearestSoupDist) {
                nearestSoupSquare = soupSquare;
                nearestSoupDist = getDistanceSquared(rc.getLocation(), soupSquare);
            }
        }

        curSoupSource = nearestSoupSquare;

        while (getDistanceSquared(rc.getLocation(), nearestSoupSquare) > 1) {
            tryMove(movementSolver.directionToGoal(nearestSoupSquare));
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
                while (soupCount[neighbour.y][neighbour.x] == null) {
                    if (tryMove(movementSolver.directionToGoal(neighbour))) {
                        searchForSoupContinued();

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

        SoupCluster found = new SoupCluster(x1, y1, x2, y2, size);

        // Check to see if other miners have already found this cluster
        boolean hasBeenBroadCasted = false;
        updateClusters();
        System.out.println("CURRENT: " + found.x1 + " " + found.y1 + " " + found.x2 + " " + found.y2);
        System.out.println("CHECKING if been found before : " + soupClusters.size());
        for (SoupCluster soupCluster : soupClusters) {
            System.out.println("Know about: " + soupCluster.x1 + " " + soupCluster.y1 + " " + soupCluster.x2 + " " + soupCluster.y2);
            if (found.inside(soupCluster)) hasBeenBroadCasted = true;
        }
        System.out.println("HAS been found before " + hasBeenBroadCasted);

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
                        if (broadcastedSoupCluster.inside(alreadyFoundSoupCluster)) seenBefore = true;
                    }

                    if (!seenBefore) {
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
        searchForSoup();

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
