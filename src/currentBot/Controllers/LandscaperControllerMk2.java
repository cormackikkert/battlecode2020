package currentBot.Controllers;

import battlecode.common.*;
import currentBot.*;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Stack;

public class LandscaperControllerMk2 extends Controller {
    /*
        Current Miner strategy
        - Have two bots that perform BFS in one half of the map
        - The bots broadcast soup cluster locations
        - Other bots get assigned a soup location to mine from
        - After mining they go to other soup locations

     */
    final int dirtLimit = RobotType.LANDSCAPER.dirtLimit;
    enum State {
        DEFEND,
        ATTACK,
        ROAM,
        REMOVE_WATER
    }

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

    State currentState = State.REMOVE_WATER;
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
    boolean[][] dumped;

    boolean[][] searchedForSoupCluster = null; // Have we already checked if this node should be in a soup cluster
    RingQueue<MapLocation> reachQueue = new RingQueue<>(PlayerConstants.SEARCH_DIAMETER * PlayerConstants.SEARCH_DIAMETER);

    SoupCluster currentSoupCluster; // Soup cluster the miner goes to
    MapLocation currentSoupSquare; // Soup square the miner mines

    LinkedList<SoupCluster> soupClusters = new LinkedList<>();

    int born;
    int lastRound = 1;

    int compressedWidth;
    int compressedHeight;
    int BLOCK_SIZE = PlayerConstants.GRID_BLOCK_SIZE;

    Symmetry searchSymmetry = null; // Symmetry that explore bot sticks too

    public LandscaperControllerMk2(RobotController rc) {
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


        soupCount = new Integer[rc.getMapHeight()][rc.getMapWidth()];
        containsWater = new Boolean[rc.getMapHeight()][rc.getMapWidth()];
        elevationHeight = new Integer[rc.getMapHeight()][rc.getMapWidth()];
        searchedForSoupCluster = new boolean[rc.getMapHeight()][rc.getMapWidth()];
        buildMap = new Integer[rc.getMapHeight()][rc.getMapWidth()];
        visited = new boolean[rc.getMapHeight()][rc.getMapWidth()];
        dumped = new boolean[rc.getMapHeight()][rc.getMapWidth()];

        compressedHeight = rc.getMapHeight() / PlayerConstants.GRID_BLOCK_SIZE + ((rc.getMapHeight() % PlayerConstants.GRID_BLOCK_SIZE == 0) ? 0 : 1);
        compressedWidth = rc.getMapWidth() / PlayerConstants.GRID_BLOCK_SIZE + ((rc.getMapWidth() % PlayerConstants.GRID_BLOCK_SIZE == 0) ? 0 : 1);

        seenBlocks = new boolean[compressedHeight][compressedWidth];

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
            case REMOVE_WATER: execRemoveWater(); break;
        }
    }

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

    static final int AROUND_FLOODED = 2;
    public int averageFloodDepth(SoupCluster soupCluster) throws GameActionException {
        int thisElevation = rc.senseElevation(rc.getLocation());
        int sum = 0;
        int div = 0;
        int x1 = soupCluster.x1, x2 = soupCluster.x2, y1 = soupCluster.y1, y2 = soupCluster.y2;
        for (int x = x1 - AROUND_FLOODED; x <= x2 + AROUND_FLOODED; x++) {
            for (int y = y1 - AROUND_FLOODED; y <= y2 + AROUND_FLOODED; y++) {
                MapLocation loc = new MapLocation(x, y);
                if (rc.canSenseLocation(loc)) {
                    div++;
                    sum += thisElevation - rc.senseElevation(loc); // how deep water level is compared to where robot is standing
                }
            }
        }
        return sum / div;
    }

    static final int WATER_DEPTH_BOTHER = 10; // the maximum water depth landscapers bother to remove water
    boolean dig = true;
    public void execRemoveWater() throws GameActionException {
        if (dig) {
            if (rc.canDigDirt(Direction.CENTER)) {
                rc.digDirt(Direction.CENTER);
            }
        }
        dig = ! dig;

        // get current soup cluster to go to
        if (currentSoupCluster == null) {
            if (soupClusters.size() > 0) {
                currentSoupCluster = soupClusters.get(0);
            } else {
                tryMove(randomDirection()); // FIXME : do something more productive
            }
        } else {
            tryMove(movementSolver.directionToGoal(currentSoupCluster.closest(rc.getLocation())));
        }

        MapLocation thisLoc = rc.getLocation();
        if (currentSoupCluster.nearCluster(rc.getLocation())) {
            int avgFlood = averageFloodDepth(currentSoupCluster);
            if (avgFlood > WATER_DEPTH_BOTHER || avgFlood <= 0) { // don't bother trying here if too deep or no water
                soupClusters.remove(currentSoupCluster);
                currentSoupCluster = null;
            } else {
                SoupCluster soupCluster = currentSoupCluster;
                Direction depositHere = null;
                for (Direction direction : directions) {
                    MapLocation dumpHere = thisLoc.add(direction);
                    if (rc.senseFlooding(dumpHere) && (dumpHere.x + dumpHere.y) % 2 == 0) {
                        depositHere = direction;
                        break;
                    }
                }

                if (depositHere != null) {
                    while (rc.senseFlooding(thisLoc.add(depositHere))) {
                        execGreedyFill(depositHere);
                    }
                } else {
                    if (soupCluster.insideThis(thisLoc)) {
                        tryMove(randomDirection());
                    } else {
                        tryMove(movementSolver.directionToGoal(soupCluster.center));
                    }
                }
            }
        }
    }

    public void execGreedyFill(Direction depositHere) throws GameActionException {
        MapLocation location = rc.getLocation();
        if (rc.getDirtCarrying() == 0) {
            for (Direction dir : Direction.allDirections()) {
                MapLocation digHere = location.add(dir);
                if (rc.canDigDirt(dir) && !dir.equals(depositHere) && !dumped[digHere.x][digHere.y] && (digHere.x + digHere.y) % 2 == 1) {
                    rc.digDirt(dir);
                    break;
                }
            }
        } else if (rc.canDepositDirt(depositHere)){
            rc.depositDirt(depositHere);
            location = location.add(depositHere);
            dumped[location.x][location.y] = true;
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
                currentState = State.ROAM;
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
        currentState = State.REMOVE_WATER;
    }

    public void execSearch() throws GameActionException {
        currentSoupSquare = null;

        SoupCluster soupCluster = searchSurroundingsSoup();
        if (soupCluster != null) {
            currentState = State.REMOVE_WATER;
            currentSoupCluster = soupCluster;
            return;
        }

        updateClusters();
        if (soupClusters != null) {
            currentState = State.REMOVE_WATER;
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

    int reduce(int val, int decay) {
        // Reduces magnitude of val by decay
        if (val > 0) return Math.max(0, val - decay);
        if (val < 0) return Math.min(0, val + decay);
        return val;
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
