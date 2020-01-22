package currentBot.Controllers;

import battlecode.common.*;
import currentBot.*;

import java.util.LinkedList;
import java.util.Stack;

import static currentBot.Controllers.PlayerConstants.*;

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
        EXPLORE,  // Uses DFS to find soup locations (DFS > BFS)
        ELEVATE,
        ELEVATE2
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

    int lastRoundReqs = 1;
    LinkedList<Company> reqs = new LinkedList<>();
    Company currentReq = null;

    int bias; // Which bias the robot has
    MapLocation BIAS_TARGET; // which square the robot is targeting
    MapLocation searchTarget;

    boolean isRush = false;

    public State currentState = State.SEARCHURGENT;
    public State previousState = null;
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
//    Integer[][] elevationHeight = null;
    Integer[][] buildMap = null; // Stores what buildings have been built and where
    int lastChecked[][] = null;
    boolean[][] searchedForSoupCluster = null; // Have we already checked if this node should be in a soup cluster
    boolean[][] dontCheckThisSoup; // really dumb water soup we cant reach
    boolean[][] cantBuildHere;

    SoupCluster currentSoupCluster; // Soup cluster the miner goes to
    MapLocation currentSoupSquare; // Soup square the miner mines
    MapLocation currentRefineryPos;

    LinkedList<SoupCluster> soupClusters = new LinkedList<>();
    LinkedList<SoupCluster> waterClusters = new LinkedList<>();

    int born;
    int lastRound = 1;

    int compressedWidth;
    int compressedHeight;
    int BLOCK_SIZE = PlayerConstants.GRID_BLOCK_SIZE;
    boolean usedDrone = false;

    Symmetry searchSymmetry = null; // Symmetry that explore bot sticks too

    RingQueue<MapLocation> reachQueue = new RingQueue<>(PlayerConstants.SEARCH_DIAMETER * PlayerConstants.SEARCH_DIAMETER);

    MapLocation last;

    boolean foundHQ = false;
    boolean shouldBuildFC = true;
    boolean shouldBuildDS = true;


    public MinerController(RobotController rc) {
        this.rc = rc;
//        System.out.println("Soup is at " + rc.getTeamSoup());
        int round = rc.getRoundNum();
        this.born = round;
        this.movementSolver = new MovementSolver(this.rc, this);
        this.communicationHandler = new CommunicationHandler(this.rc, this);
        queue = new RingQueue<>(rc.getMapHeight() * rc.getMapWidth());
        getInfo(rc);

//        System.out.println("I got built on round " + this.rc.getRoundNum());
        bias = (int) (Math.random() * BIAS_TYPES);

        // Check if a fulfillment center hasn't been built already
        for (RobotInfo robotInfo : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (robotInfo.getType() == RobotType.HQ) {foundHQ = true; allyHQ = robotInfo.location;}
            if (robotInfo.getType() == RobotType.FULFILLMENT_CENTER) {shouldBuildFC = false;}
            if (robotInfo.getType() == RobotType.DESIGN_SCHOOL) {shouldBuildDS = false;}
        }

        if (shouldBuildFC && foundHQ &&
                rc.getTeamSoup() > RobotType.FULFILLMENT_CENTER.cost) {
            System.out.println("YES build drones");
            currentState = State.BUILDER;
            buildType = RobotType.FULFILLMENT_CENTER;
            buildLoc = null;
        } else if (shouldBuildDS && foundHQ &&
                rc.getTeamSoup() > RobotType.DESIGN_SCHOOL.cost) {
//                && rc.getRoundNum() >= START_BUILD_WALL) {
            System.out.println("YES build landscapers");
            currentState = State.BUILDER;
            buildType = RobotType.DESIGN_SCHOOL;
        }

        soupCount = new Integer[rc.getMapHeight()][rc.getMapWidth()];
        containsWater = new Boolean[rc.getMapHeight()][rc.getMapWidth()];
        elevationHeight = new Integer[rc.getMapHeight()][rc.getMapWidth()];
        searchedForSoupCluster = new boolean[rc.getMapHeight()][rc.getMapWidth()];
        buildMap = new Integer[rc.getMapHeight()][rc.getMapWidth()];
        visited = new boolean[rc.getMapHeight()][rc.getMapWidth()];
        lastChecked   = new int[rc.getMapHeight()][rc.getMapWidth()];
        dontCheckThisSoup = new boolean[rc.getMapHeight()][rc.getMapWidth()];
        cantBuildHere = new boolean[rc.getMapHeight()][rc.getMapWidth()];

        compressedHeight = rc.getMapHeight() / PlayerConstants.GRID_BLOCK_SIZE + ((rc.getMapHeight() % PlayerConstants.GRID_BLOCK_SIZE == 0) ? 0 : 1);
        compressedWidth = rc.getMapWidth() / PlayerConstants.GRID_BLOCK_SIZE + ((rc.getMapWidth() % PlayerConstants.GRID_BLOCK_SIZE == 0) ? 0 : 1);

        seenBlocks = new boolean[compressedHeight][compressedWidth];
        // searchSurroundingsContinued();
        last = rc.getLocation();
        currentRefineryPos = allyHQ;

        if (born == 2) {
            searchSymmetry = Symmetry.VERTICAL;
            currentState = State.EXPLORE;
        } else if (born == 3) {
            searchSymmetry = Symmetry.HORIZONTAL;
            currentState = State.EXPLORE;
        }
//        else if (born == RUSH1 || born == RUSH2 || born == RUSH3) {
//            currentState = State.RUSHBOT;
//        }


        try {
            enemyHQ = communicationHandler.receiveEnemyHQLoc();
        } catch (Exception e) {
            e.printStackTrace();
        }

        //System.out.println("Spawned: " + builtFC + " " + foundHQ + " " + PlayerConstants.buildSoupRequirements(RobotType.FULFILLMENT_CENTER));// + " " + (rc.getTeamSoup() > PlayerConstants.buildSoupRequirements(RobotType.FULFILLMENT_CENTER)));


    }


    public void run() throws GameActionException {
        if (this.currentSoupCluster != null) this.currentSoupCluster.draw(this.rc);

        hqInfo(); // includes scanning robots
        scanNetGuns();
        solveGhostHq();
        updateReqs();
        commitSudoku(); // if stuck
        evacuate(); // go to a different soup cluster

        for (Direction dir : Direction.allDirections()) {
            System.out.println(dir);
        }

        communicationHandler.solveEnemyHQLocWithGhosts();

        updateClusters();

        if (currentRefineryPos == allyHQ) {
            for (RobotInfo ally : allies) {
                if (ally.getType() == RobotType.REFINERY) {
                    currentRefineryPos = ally.getLocation();
                }
            }
        }

        avoidDrone();
        if ((rc.senseElevation(rc.getLocation()) < GameConstants.getWaterLevel(rc.getRoundNum() + 1)) &&
            isAdjacentToWater(rc.getLocation())) {
            avoidWater();
        }

        System.out.println("I am a " + currentState + " " + soupClusters.size() + " " + buildType);

        if (rc.senseElevation(rc.getLocation()) > GameConstants.getWaterLevel(rc.getRoundNum() + 300) &&
                rc.getTeamSoup() > PlayerConstants.buildSoupRequirements(RobotType.VAPORATOR) &&
                !shouldBuildDS && !shouldBuildFC) {
            currentState = State.BUILDER;

            buildType = RobotType.VAPORATOR;
            buildLoc = null;
        }

        // Instead only elevate when a landscaper asks too
//        if (rc.getRoundNum() >= ELEVATE_BUILD) {
//            currentState = State.ELEVATE;
//        }

        updateReqs();
        if (currentReq != null) {
            execElevate();
            return;
        }

        switch (currentState) {
            case SEARCH: execSearch();             break;
            case MINE: execMine();                 break;
            case DEPOSIT: execDeposit();           break;
            case SEARCHURGENT: execSearchUrgent(); break;
            case BUILDER: execBuilder();           break;
            case SCOUT: execScout();               break;
            case RUSHBOT: execRush();              break;
            case EXPLORE: execExplore();           break;
            case ELEVATE: execElevate();           break;
            case ELEVATE2: execElevate2();         break;
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

                if (rc.getRoundNum() - lastChecked[sensePos.y][sensePos.x] <= 10) continue;
                lastChecked[sensePos.y][sensePos.x] = rc.getRoundNum();

                if (soupCount[sensePos.y][sensePos.x] != null) continue;

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
                if (!rc.canSenseLocation(sensePos)) continue;
                if (containsWater[sensePos.y][sensePos.x]) soupCount[sensePos.y][sensePos.x] = 0;

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
        //boolean xup = (last.x <= rc.getLocation().x);
        //boolean yup = (last.y <= rc.getLocation().y);

        for (int dx = -3; dx <= 3; ++dx) {
            for (int dy = -3; dy <= 3; ++dy) {
                MapLocation sensePos = new MapLocation(rc.getLocation().x + dx, rc.getLocation().y + dy);
                if (!rc.canSenseLocation(sensePos)) continue;

                if (rc.getRoundNum() - lastChecked[sensePos.y][sensePos.x] <= 10) continue;
                lastChecked[sensePos.y][sensePos.x] = rc.getRoundNum();

                // if (soupCount[sensePos.y][sensePos.x] != null) continue;

                // Check elevation
                elevationHeight[sensePos.y][sensePos.x] = rc.senseElevation(sensePos);

                // Check water
                containsWater[sensePos.y][sensePos.x] = rc.senseFlooding(sensePos);

                /*
                // Check robot
                RobotInfo robot = rc.senseRobotAtLocation(sensePos);
                if (robot != null && (robot.type == RobotType.REFINERY)) {
//                    System.out.println("Found refinery " + sensePos + " " + robot.type.ordinal());
                    buildMap[sensePos.y][sensePos.x] = robot.type.ordinal();
//                    System.out.println(buildMap[sensePos.y][sensePos.x]);
                }

                 */

                soupCount[sensePos.y][sensePos.x] = rc.senseSoup(sensePos);
            }
        }
    }

    public void searchSurroundingsContinuedSmall() throws GameActionException {
        /*
            Like searchSurroundings, but doesnt try to determine which cluster a soup position is from
         */

        for (int dx = -4; dx <= 4; ++dx) {
            for (int dy = -4; dy <= 4; ++dy) {
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
        System.out.println("Doing mining stuff " + currentSoupSquare);

        updateClusters();

//        if (rc.getRoundNum() > 100 && currentSoupCluster.size == 0) {
//            // TODO: do something
//        }

        searchSurroundingsContinued();

        // If you can no longer carry any soup deposit it
        if (rc.getSoupCarrying() + GameConstants.SOUP_MINING_RATE > RobotType.MINER.soupLimit) {
            currentState = State.DEPOSIT;
            System.out.println("Deposit now");
            execDeposit();
            return;
        }

        if (currentSoupSquare == null) {
            int closest = 100;
            currentSoupSquare = null;

            boolean containsWaterTile = false;

            for (MapLocation square : rc.senseNearbySoup()) {
                if (!rc.canSenseLocation(square)) continue;
                if (rc.sensePollution(square) > MINER_POLLUTION_THRESHOLD) continue;

                boolean surroundedByWater = true;
                for (Direction dir : Direction.allDirections()) {
                    if (rc.canSenseLocation(square.add(dir)) && !rc.senseFlooding(square.add(dir))) surroundedByWater = false;
                }

                if (surroundedByWater && Math.abs(rc.senseElevation(square) - rc.senseElevation(rc.getLocation())) <= BOTHER_DIGGING) {
                    containsWaterTile = true;
                    continue;
                }

                if (dontCheckThisSoup[square.y][square.x]) continue;

                int dist = getChebyshevDistance(rc.getLocation(), square);
                if (dist < closest) {
                    closest = dist;
                    currentSoupSquare = square;
                }
            }

            if (currentSoupSquare == null) {
                currentState = State.SEARCHURGENT;

                if (containsWaterTile) {
                    // this might be useful: if (rc.senseElevation(currentSoupSquare) >= rc.senseElevation(rc.getLocation()) - PlayerConstants.BOTHER_DIGGING) {
                    if (!communicationHandler.seenClearSoupCluster(currentSoupCluster)) {
                        communicationHandler.askClearSoupFlood(currentSoupCluster);
                        buildType = RobotType.DESIGN_SCHOOL;
                        currentState = State.BUILDER;
                        buildLoc = null;
                        execBuilder();
                    }
                    // go to other cluster in the mean time
                    soupClusters.remove(currentSoupCluster);
                    waterClusters.add(currentSoupCluster);
                    currentSoupSquare = null;
                    currentState = State.SEARCHURGENT;
                } else {
                    // Communicate that the soup cluster is finished
                    if (currentSoupCluster != null) {
                        currentSoupCluster.size = 0;
//            System.out.println("This cluster is finished");
                        communicationHandler.sendCluster(currentSoupCluster);
                    }
                }
                // Reset variables
                if (currentSoupCluster != null) soupClusters.remove(currentSoupCluster);
                currentSoupCluster = null;
                currentSoupSquare = null;
                //execSearchUrgent();
                return;
            }
            if (!isAdjacentTo(currentSoupSquare)) {
                if (!canReach(currentSoupSquare)) {
                    // Drone case
                    getHitchHike(rc.getLocation(), currentSoupSquare);
                }
            }

        }

        if (!isAdjacentTo(currentSoupSquare)) {
            if (movementSolver.moves > PlayerConstants.GIVE_UP_THRESHOLD) {
                dontCheckThisSoup[currentSoupSquare.y][currentSoupSquare.x] = true;
                currentSoupSquare = null;
                movementSolver.moves = 0;
            }
            else tryMove(movementSolver.directionToGoal(currentSoupSquare));
        } else if (rc.senseSoup(currentSoupSquare) > 0) {
            if (rc.canMineSoup(rc.getLocation().directionTo(currentSoupSquare))) {
                rc.mineSoup(rc.getLocation().directionTo(currentSoupSquare));
                System.out.println("mine soup");
            }
        } else {
            soupCount[currentSoupSquare.y][currentSoupSquare.x] = 0;
            currentSoupSquare = null;
        }
    }

    public void execDeposit() throws GameActionException {
        searchSurroundingsContinued();

        if (currentRefineryPos == null ||
            getChebyshevDistance(rc.getLocation(), currentRefineryPos) > PlayerConstants.DISTANCE_FROM_REFINERY ||
                (currentRefineryPos.equals(allyHQ) && ((!shouldBuildDS && !shouldBuildFC) || usedDrone))) {

            MapLocation oldRefPos = currentRefineryPos;
            currentRefineryPos = null;

            // Look for new refinery
            for (RobotInfo robot : rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared())) {
                if (robot.type == RobotType.REFINERY) {
                    if (oldRefPos != null && oldRefPos.equals(robot.location)) continue;
                    if (currentRefineryPos == null ||
                        getChebyshevDistance(rc.getLocation(), robot.location) < getChebyshevDistance(rc.getLocation(), currentRefineryPos)) {
                        currentRefineryPos = robot.location;
                    }
                }
            }

            System.out.println("Found closest refinery");

            if (currentRefineryPos != null && getChebyshevDistance(rc.getLocation(), oldRefPos) <= getChebyshevDistance(rc.getLocation(), currentRefineryPos)) {
                currentRefineryPos = null;
            }

            if (currentRefineryPos == null && rc.getTeamSoup() > RobotType.REFINERY.cost) {
                // Build a new refinery
                while (currentRefineryPos == null) {
                    System.out.println("I am looking for a place to build a refinery");
                    for (Direction dir : Direction.allDirections()) {
                        if (getChebyshevDistance(allyHQ, rc.getLocation().add(dir)) <= 2) continue;
                        if (tryBuild(RobotType.REFINERY, dir)) {
//                            communicationHandler.transmitNewRefinery();
                            currentRefineryPos = rc.getLocation().add(dir);
                            break;
                        }
                    }

                    if (currentRefineryPos == null) {
                        // Couldn't build a refinery
                        currentRefineryPos = allyHQ;
                        break;
                    }

                    while (!rc.isReady()) Clock.yield();
                    tryMove(movementSolver.directionToGoal(rc.getLocation().add(allyHQ.directionTo(rc.getLocation()))));
                    Clock.yield();
                }
            } else if (currentRefineryPos == null) {
                currentRefineryPos = allyHQ;
            }
        }

        boolean nearbyNetGun = false;
        for (RobotInfo robot : rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rc.getTeam())) {
            if (robot.type == RobotType.NET_GUN) nearbyNetGun = true;
        }

        boolean nearbyDrone = false;
        for (RobotInfo robot : rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rc.getTeam().opponent())) {
            if (robot.type == RobotType.DELIVERY_DRONE) nearbyDrone = true;
        }

        if (nearbyDrone && !nearbyNetGun && rc.getTeamSoup() > PlayerConstants.buildSoupRequirements(RobotType.NET_GUN)) {
            buildType = RobotType.NET_GUN;
            buildLoc = null;
            execBuilder();
        }

        if (isAdjacentTo(currentRefineryPos)) {
            if (rc.canDepositSoup(rc.getLocation().directionTo(currentRefineryPos))) {
                rc.depositSoup(rc.getLocation().directionTo(currentRefineryPos), rc.getSoupCarrying());

                if (rc.senseElevation(rc.getLocation()) > GameConstants.getWaterLevel(rc.getRoundNum() + 100) &&
                    !cantBuildHere[rc.getLocation().y][rc.getLocation().x]) {
                    if (shouldBuildDS) {
                        System.out.println("Building DS");
                        buildType = RobotType.DESIGN_SCHOOL;
                        currentState = State.BUILDER;
                        buildLoc = null;
                        return;
                    }

                    if (shouldBuildFC) {
                        System.out.println("Building FC");
                        buildType = RobotType.FULFILLMENT_CENTER;
                        currentState = State.BUILDER;
                        buildLoc = null;
                        return;
                    }
                }

                currentState = State.MINE;
            }
        } else {
            tryMove(movementSolver.directionToGoal(currentRefineryPos));
            System.out.println("Going to refinery: " +  movementSolver.moves + " " + currentRefineryPos);
            if (movementSolver.moves > GIVE_UP_THRESHOLD) {
                // Build refinery
                // Check to see if there is a closer refinery

                // Go closer to original soup square
                if (!isAdjacentTo(currentSoupSquare)) {
                    for (int i = 0; i < 10 && !isAdjacentTo(currentSoupSquare); ++i) {
                        tryMove(movementSolver.directionToGoal(currentSoupSquare));
                        Clock.yield();
                    }
                }
                if (!cantBuildHere[rc.getLocation().y][rc.getLocation().x]) {
                    if (rc.getRoundNum() > 450 && currentRefineryPos.equals(allyHQ)) {
                        // lol idk what goes here (miner is kinda screwed)
                        Clock.yield();
                    } else {
                        getHitchHike(rc.getLocation(), currentRefineryPos);
                    }
                }

                boolean gotHitchHike = false;
                for (RobotInfo robot : rc.senseNearbyRobots()) {
                    if (robot.type == RobotType.REFINERY) {
                        getHitchHike(rc.getLocation(), robot.location);
                        gotHitchHike = true;
                    }
                }
                if (!gotHitchHike) {
                    buildType = RobotType.REFINERY;
                    currentState = State.BUILDER;
                    buildLoc = null;
                    execBuilder();
                }

                movementSolver.moves = 0;
                currentState = State.MINE;
            }
        }
    }
    boolean builtFC = false;
    public void execSearchUrgent() throws GameActionException {
        // Decide which cluster each miner goes to by using ID's
        // So each cluster has the number of miners proportional to its size

        for (Direction dir : Direction.allDirections()) {
            if (!rc.canSenseLocation(rc.getLocation().add(dir))) continue;
            if (rc.senseSoup(rc.getLocation().add(dir)) > 0 && rc.canMineSoup(dir)) {
                currentState = State.MINE;
                execMine();
                return;
            }
        }
        if (currentSoupCluster == null) {
            System.out.println(1);
            System.out.println(currentSoupCluster);
            currentSoupSquare = null;
            updateClusters();

            int totalSoupSquares = 0;

            SoupCluster nextBest = soupClusters.get(0);
            for (SoupCluster soupCluster : soupClusters) {
                if (soupCluster.elevation > GameConstants.getWaterLevel(rc.getRoundNum() + 300))
                    totalSoupSquares += soupCluster.size;
                else {
                    if (soupCluster.elevation > nextBest.elevation) nextBest = soupCluster;
                }
            }

            if (totalSoupSquares == 0) {
                if (nextBest != null) {
                    currentSoupCluster = nextBest;
                    return;
                }

                // TODO: something better
                soupClusters = waterClusters;
                if (waterClusters.size() == 0 && rc.getRoundNum() > 500 && !builtFC) {
                    while (!rc.isReady()) Clock.yield();
                    for (Direction dir : Direction.allDirections()) {
                        if (rc.canBuildRobot(RobotType.FULFILLMENT_CENTER, dir)) {
                            rc.buildRobot(RobotType.FULFILLMENT_CENTER, dir);
                            builtFC = true;
                        }
                    }
                }
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

            if (currentSoupCluster == null && rc.getRoundNum() > 800) {
                this.buildType = RobotType.FULFILLMENT_CENTER;
                this.buildLoc = null;
                currentState = State.BUILDER;
                execBuilder();
                return;
                // Explore time
                // System.out.println("YASSS");
            }
            System.out.println(currentSoupCluster);
        }

//        System.out.println(currentSoupCluster.middle + " " + currentSoupSquare);

        if (currentSoupCluster != null && !rc.getLocation().equals(currentSoupCluster.closest(rc.getLocation()))) {
            System.out.println(2);
            /*
                Now as we have dedicated searches we only focus on getting to the soup cluster

            SoupCluster soupCluster = searchSurroundingsSoup();
            if (soupCluster != null) {
                currentSoupCluster = soupCluster;
            }

             */
//            System.out.println("Heading to soup cluster at " + currentSoupCluster.toStringPos());
            tryMove(movementSolver.directionToGoal(currentSoupCluster.middle));
            searchSurroundingsContinued();
            System.out.println("can I make it? " + rc.getLocation() + " " + currentSoupCluster.closest(rc.getLocation()));
            if (!canReach(currentSoupCluster.middle) && !currentSoupCluster.contains(rc.getLocation())) {
                rc.setIndicatorDot(rc.getLocation(), 255, 0, 0);
                System.out.println("Asking for assistance");
                getHitchHike(rc.getLocation(), currentSoupCluster.middle);
                System.out.println("landed");
                if (getChebyshevDistance(rc.getLocation(), currentSoupCluster.closest(rc.getLocation())) > 5) {
                    currentSoupCluster.size = 0;
                    communicationHandler.sendCluster(currentSoupCluster);
                } else {
                    currentState = State.MINE;
                    usedDrone = true;
                    currentRefineryPos = null;
                    shouldBuildDS = true;
                    shouldBuildFC = true;
                    searchSurroundingsSoup();
                }

            } else {
                System.out.println(3);
//                System.out.println("true");
            }

        } else {
            System.out.println(4);
            currentState = State.MINE;
            execMine();
        }

    }

    public void evacuate() throws GameActionException {
        if (currentSoupCluster == null) return;
        if (currentSoupCluster.elevation < GameConstants.getWaterLevel(rc.getRoundNum() + 100)) {
            currentSoupCluster.size = 0;
            communicationHandler.sendCluster(currentSoupCluster);

            updateClusters();

            int totalSoupSquares = 0;

            SoupCluster nextBest = soupClusters.get(0);
            for (SoupCluster soupCluster : soupClusters) {
                if (soupCluster.elevation > GameConstants.getWaterLevel(rc.getRoundNum() + 300))
                    totalSoupSquares += soupCluster.size;
                else {
                    if (soupCluster.elevation > nextBest.elevation) nextBest = soupCluster;
                }
            }

            if (totalSoupSquares == 0) {
                if (nextBest != null) {
                    currentSoupCluster = nextBest;
                    return;
                }

                // TODO: something better
                soupClusters = waterClusters;
                if (waterClusters.size() == 0 && rc.getRoundNum() > 500 && !builtFC) {
                    while (!rc.isReady()) Clock.yield();
                    for (Direction dir : Direction.allDirections()) {
                        if (rc.canBuildRobot(RobotType.FULFILLMENT_CENTER, dir)) {
                            rc.buildRobot(RobotType.FULFILLMENT_CENTER, dir);
                            builtFC = true;
                        }
                    }
                }
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

            if (currentSoupCluster == null && rc.getRoundNum() > 800) {
                this.buildType = RobotType.FULFILLMENT_CENTER;
                this.buildLoc = null;
                currentState = State.BUILDER;
                execBuilder();
                return;
                // Explore time
                // System.out.println("YASSS");
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
        int waterSize = 0;
        boolean containsWaterSoup = false;

        int totalElevation = 0; // Used for finding mean elevation

        int x1 = pos.x;
        int x2 = pos.x;
        int y1 = pos.y;
        int y2 = pos.y;

        // Incase the enemy has already occupied this spot
        MapLocation refineryPos = null;

        while (!queue.isEmpty() && (x2 - x1) * (y2 - y1) <= 50) {
            MapLocation current = queue.poll();
            System.out.println("Determining cluster: " + current);

//            visited[current.y][current.x] = true;

            x1 = Math.min(x1, current.x);
            x2 = Math.max(x2, current.x);

            y1 = Math.min(y1, current.y);
            y2 = Math.max(y2, current.y);

            // Determine if we already know about this cluster
            // We keep searching instead of returning to mark each cell as checked
            // so we don't do it again
            ++size;

            // Dont count elevation of water tiles as we won't be stepping there
            if (containsWater[current.y][current.x] != null &&
                    containsWater[current.y][current.x]) waterSize += 1;
            else
                totalElevation += (elevationHeight[current.y][current.x] != null) ? elevationHeight[current.y][current.x] : 0;

            for (Direction delta : Direction.allDirections()) {
                MapLocation neighbour = current.add(delta);
                if (!inRange(neighbour.y, 0, rc.getMapHeight()) || !inRange(neighbour.x, 0, rc.getMapWidth())) continue;
                if (searchedForSoupCluster[neighbour.y][neighbour.x]) continue;

                // If you cant tell whether neighbour has soup or not move closer to it

                while (!rc.isReady()) Clock.yield();

                boolean isPossible = true;
                while (soupCount[neighbour.y][neighbour.x] == null){
                    if (!canReach(neighbour) || movementSolver.moves > GIVE_UP_THRESHOLD) {
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
                if (containsWater[neighbour.y][neighbour.x]) continue;

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

        SoupCluster found = new SoupCluster(x1, y1, x2, y2, size, crudeSoup, waterSize, totalElevation / size);

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

    public void execBuilder() throws GameActionException {



        if (buildLoc == null) {
            buildLoc = getNearestBuildTile();
            System.out.println("buildLoc: " + buildLoc);
            if (buildLoc == null) {
                System.out.println("Couldnt find one");
                currentState = State.MINE;
                execMine();
                return;
            }
        }


        if (!isAdjacentTo(buildLoc)) {
            System.out.println("Moving closer");
            if (movementSolver.moves > GIVE_UP_THRESHOLD) {
                if (rc.onTheMap(buildLoc.add(Direction.NORTH)))
                    getHitchHike(rc.getLocation(), buildLoc.add(Direction.NORTH));
                else
                    getHitchHike(rc.getLocation(), buildLoc.add(Direction.SOUTH));
                currentState = State.MINE;
//                cantBuildHere[rc.getLocation().y][rc.getLocation().x] = true;
                execMine();
                return;
            }
            tryMove(movementSolver.directionToGoal(buildLoc));
        } else {
            if (buildType == null) {
                switch (rc.getRoundNum() % 10) {
                    case 0: case 1: case 3: case 4: case 6: case 7: case 9: buildType = RobotType.FULFILLMENT_CENTER; break;
                    default: buildType = RobotType.VAPORATOR;
                }
            }
            while (!rc.isReady()) Clock.yield();

            if (PlayerConstants.shouldntDuplicate(this.buildType)) {
                for (RobotInfo robot : rc.senseNearbyRobots()) {
                    if (robot.team == rc.getTeam() && robot.type == this.buildType) {

                        if (this.buildType == RobotType.DESIGN_SCHOOL) shouldBuildDS = false;
                        if (this.buildType == RobotType.FULFILLMENT_CENTER) shouldBuildFC = false;

                        currentState = State.MINE;
                        execMine();
                        return;
                    }
                }
            }

            if (rc.getTeamSoup() > PlayerConstants.buildSoupRequirements(this.buildType)) {
                if (tryBuild(this.buildType, rc.getLocation().directionTo(buildLoc)) ||
                        (rc.senseRobotAtLocation(buildLoc) != null && rc.senseRobotAtLocation(buildLoc).type == buildType)) {
                    if (this.buildType == RobotType.DESIGN_SCHOOL) shouldBuildDS = false;
                    if (this.buildType == RobotType.FULFILLMENT_CENTER) shouldBuildFC = false;
                    System.out.println("built");
                    currentState = State.MINE;
                } else {
                    currentState = State.MINE;
                    execMine();
                    return;
                }
            } else {
                System.out.println("failed: not enough soup");
                currentState = State.MINE;
            }
        }
        movementSolver.moves = 0;
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
            case RUSH1 :
                candidateEnemyHQ = new MapLocation(rc.getMapWidth() - allyHQ.x - 1, allyHQ.y);
                break;
            case RUSH2:
                candidateEnemyHQ = new MapLocation(allyHQ.x, rc.getMapHeight() - allyHQ.y - 1);
                break;
            case RUSH3:
                candidateEnemyHQ = new MapLocation(rc.getMapWidth() - allyHQ.x - 1, rc.getMapHeight() - allyHQ.y - 1);
                break;
            default:
                candidateEnemyHQ = new MapLocation(rc.getMapWidth() - allyHQ.x - 1, rc.getMapHeight() - allyHQ.y - 1);
//                System.out.println("REEE");

        }

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
            updateClusters();

            // Broadcast what areas have been searched
            if (!seenBlocks[node.y / BLOCK_SIZE][node.x / BLOCK_SIZE]) {
                visitedBlocks.add(new MapLocation(node.x / BLOCK_SIZE, node.y / BLOCK_SIZE));
                seenBlocks[node.y / BLOCK_SIZE][node.x / BLOCK_SIZE] = true;

                if (visitedBlocks.size() == 12) {
                    communicationHandler.sendMapBlocks(visitedBlocks.toArray(new MapLocation[0]));
//                    System.out.println("Sending " + visitedBlocks.size());
                    visitedBlocks = new LinkedList<>();
                }
            }

            updateSeenBlocks();

            if (visited[node.y][node.x]) continue;
            visited[node.y][node.x] = true;

            for (Direction dir : Direction.allDirections()) {
                MapLocation nnode = node.add(dir).add(dir);
                if (!rc.onTheMap(nnode) || visited[nnode.y][nnode.x]) continue;

                //System.out.println("starting");
//                canReach(nnode);
                //System.out.println("Done");
                int originalDistance = getChebyshevDistance(rc.getLocation(), nnode);

                while (canReach(nnode) && movementSolver.moves < GIVE_UP_THRESHOLD + originalDistance && soupCount[nnode.y][nnode.x] == null) {
                    tryMove(movementSolver.directionToGoal(nnode));
                    searchSurroundingsSoup();

                    Clock.yield();
                    updateClusters();
                }


                if (soupCount[nnode.y][nnode.x] == null) continue;

                if (containsWater[nnode.y][nnode.x]) continue;

                // Don't explore if this node goes out side of the region of symmetry
                if (searchSymmetry == Symmetry.VERTICAL) {
                    if (getDistanceSquared(nnode, allyHQ) > getDistanceSquared(nnode,
                            new MapLocation(rc.getMapWidth() - allyHQ.x - 1, allyHQ.y))) continue;
                } else if (searchSymmetry == Symmetry.HORIZONTAL) {
                    if (getDistanceSquared(nnode, allyHQ) > getDistanceSquared(nnode,
                            new MapLocation(allyHQ.x, rc.getMapHeight() - allyHQ.y - 1))) continue;
                }

                stack.push(nnode);
//                if (Math.abs(elevationHeight[nnode.y][nnode.x] - elevationHeight[node.y][node.x]) <= 3) {
//                    stack.push(nnode);
//                }
            }
        }
        if (visitedBlocks.size() > 0) {
            communicationHandler.sendMapBlocks(visitedBlocks.toArray(new MapLocation[0]));
        }
        currentState = State.SEARCHURGENT;
    }

    Direction landscaperDirection = null;
    int pointer = 0;
    public int elevateRoleStart = 0;
    public void execElevate() throws GameActionException {
        MapLocation mapLocation = rc.getLocation();
        MapLocation landscaperLocation = currentReq.landscaperPos;

        if (!mapLocation.isAdjacentTo(landscaperLocation)) {
            getHitchHike(rc.getLocation(), currentReq.landscaperPos);
//            tryMove(movementSolver.directionToGoal(landscaperLocation));
        } else {
            elevateRoleStart++;
            int highestNextElevation = 0;
            for (Direction direction : directions) {
                highestNextElevation = Math.max(highestNextElevation, rc.senseElevation(mapLocation.add(direction)));
            }
            if (elevateRoleStart > 10 && rc.senseElevation(landscaperLocation) - highestNextElevation > 5) { // means not chosen
//                for (Direction direction : directions) {
//                    RobotInfo robotInfo = rc.senseRobotAtLocation(landscaperLocation.add(direction));
//                    if (robotInfo != null && robotInfo.getTeam() == ALLY && robotInfo.getType() == RobotType.MINER) {
////                        for (int i = 0; i < landscaperLocations.size(); i++) {
////                            if (landscaperLocations.get(i).equals(landscaperLocation)) {
////                                System.out.println("remove");
////                                landscaperLocations.remove(i);
////                                break;
////                            }
////                        }
                        pointer++;
                        if (pointer < landscaperLocations.size()) {
                            landscaperLocation = landscaperLocations.get(pointer);
                            System.out.println("try again");
//                            execElevate();
                        } else {
                            System.out.println("revert back to "+previousState);
                            currentState = previousState;
                        }
//                    }
//                }
            }

            if (landscaperDirection == null) {
                landscaperDirection = mapLocation.directionTo(landscaperLocation);
            }

            if (rc.getTeamSoup() > 500) {
                switch (rc.getRoundNum() % 10) {
//                        case 0: case 1: case 3: case 4: case 6: case 7: case 9: buildType = RobotType.VAPORATOR; break;
//                        default: buildType = RobotType.FULFILLMENT_CENTER;
                    case 0: case 3: case 6: buildType = RobotType.FULFILLMENT_CENTER; break;
                    default: buildType = RobotType.VAPORATOR;
                }
                if (rc.getTeamSoup() < 1000 && vaporatorsBuilt == 0) buildType = RobotType.VAPORATOR;
                for (Direction direction : directions) {
                    if (rc.canSenseLocation(mapLocation.add(direction))
                            && rc.senseElevation(mapLocation.add(direction)) > ELEVATE_ENOUGH - 5) {
                        if (vaporatorsBuilt % 3 == 0) {
                            buildType = RobotType.FULFILLMENT_CENTER;
                            vaporatorsBuilt = 0;
                        }
                        if (tryBuild(buildType, direction)) {
                            if (buildType == RobotType.VAPORATOR) {
                                vaporatorsBuilt++;
                            }
                            System.out.println("built "+buildType+" as an elevator");
                        }
                    }
                }
            }
        }
    }
    public int vaporatorsBuilt = 0;

    public void execElevate2() throws GameActionException {
        tryMultiBuild(RobotType.FULFILLMENT_CENTER);
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

    public boolean canReach(MapLocation pos) throws GameActionException {
        int diam = PlayerConstants.SEARCH_DIAMETER;

        if (pos.equals(rc.getLocation())) return true;

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
                if (!rc.canSenseLocation(nnode)) continue;

                if (nnode.equals(pos)) return true;

                int dx = nnode.x - (rc.getLocation().x - diam/2);
                int dy = nnode.y - (rc.getLocation().y - diam/2);
                if (dx < 0 || dx >= diam || dy < 0 || dy >= diam) continue;

                if (visited[dy][dx]) continue;

                RobotInfo robot = rc.senseRobotAtLocation(nnode);
                if (robot != null && (robot.type == RobotType.REFINERY ||
                        robot.type == RobotType.HQ ||
                        robot.type == RobotType.VAPORATOR ||
                        robot.type == RobotType.FULFILLMENT_CENTER ||
                        robot.type == RobotType.DESIGN_SCHOOL)) continue;

                if (rc.senseFlooding(nnode)) continue;
                if (Math.abs(rc.senseElevation(nnode) - rc.senseElevation(node)) > 3) continue;
                visited[dy][dx] = true;
                reachQueue.add(nnode);
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
        for (int i = 0; i < 64 / BLOCK_SIZE + 10; ++i) {
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

        if (hasBeenPickedUp && (rc.getLocation().equals(oldPos) || getChebyshevDistance(rc.getLocation(), currentSoupCluster.closest(rc.getLocation())) > 5)) {
            currentSoupCluster.size = 0;
//            System.out.println("This cluster is finished");
            communicationHandler.sendCluster(currentSoupCluster);
            currentState = State.SEARCHURGENT;
        } else {
            usedDrone = true;
            shouldBuildDS = false;
            shouldBuildFC = false;
            currentRefineryPos = null;
            currentSoupSquare = null;
        }
    }

    void updateReqs() throws GameActionException {
        for (int i = lastRoundReqs; i < rc.getRoundNum(); ++i) {
            for (Transaction tx : rc.getBlock(i)) {
                int[] mess = tx.getMessage();
                if (communicationHandler.identify(mess) == CommunicationHandler.CommunicationType.ASK_COMPANY) {
                    Company req = communicationHandler.getCompany(mess);
                    req.roundNum = i;
                    reqs.add(req);
                }
                if (communicationHandler.identify(mess) == CommunicationHandler.CommunicationType.ASK_COMPANY_ACK) {
                    Company ack = communicationHandler.getCompanyAck(mess);
                    if (currentReq != null) {
                        if (currentReq.landscaperPos.equals(ack.landscaperPos)) {
                            if (ack.minerID == rc.getID()) {
                                currentReq.confirmed = true;
                            } else if (!currentReq.confirmed) {
                                currentReq = null;
                                currentState = State.MINE;
                            }
                        }
                    }
                    reqs.removeIf(r -> r.landscaperPos.equals(ack.landscaperPos));
                }
            }
        }
        if (currentReq == null) {
            for (Company req : reqs) {
                if ((rc.getRoundNum() - req.roundNum - 1) == getChebyshevDistance(rc.getLocation(), req.landscaperPos) / GRID_BLOCK_SIZE ||
                        rc.getRoundNum() - req.roundNum - 1 > 64 / GRID_BLOCK_SIZE + 1) {
                    System.out.println("I'll pick you up: " + req.toString());
                    currentReq = req;
                    currentReq.minerID = rc.getID();
                    communicationHandler.sendCompanyAck(req);
                    currentState = State.ELEVATE;
                }
            }
        }
        System.out.println("Current req: " + currentReq);
        lastRoundReqs = rc.getRoundNum(); // Keep track of last round we scanned the block chain
    }
}
