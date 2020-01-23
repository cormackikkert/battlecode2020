package whyPermutator.Controllers;

import battlecode.common.*;
import whyPermutator.*;

import java.util.Deque;
import java.util.LinkedList;
import java.util.Random;
import java.util.Stack;

import static whyPermutator.Controllers.PlayerConstants.*;

/**
 * New strategy since 14/01/2020:
 * > get soup
 * > landscapers
 * > drone defence
 * > drone attack (muddle up enemy formations)
 */
public class DeliveryDroneControllerMk2 extends Controller {

    public enum State {
        DEFEND,
        DEFENDLATEGAME,
        ATTACKLATEGAME,
        ATTACK,
        WANDER,
        EXPLORE, // Search for soup clustsers in places the miner couldn't reach
        TAXI,
        TAXI2,
        STUCKKILL,
        WANDERLATE
    }

    public State currentState = null;
    public boolean defendLateGameShield = false;

    Deque<HitchHike> reqs = new LinkedList<>();

    MapLocation nearestWaterTile;
    LinkedList<SoupCluster> soupClusters = new LinkedList<>();

    // Variables relating to compressing the map into blocks
    int compressedWidth;
    int compressedHeight;
    int BLOCK_SIZE = PlayerConstants.GRID_BLOCK_SIZE;

    Random random = new Random();

    Integer[][] soupCount = null;
    Integer[][] elevationHeight = null;
    Integer[][] buildMap = null; // Stores what buildings have been built and where
    boolean[][] searchedForSoupCluster = null; // Have we already checked if this node should be in a soup cluster

    int lastRound = 1;
    int lastRoundReqs = 1;
    boolean hasExplored = false;
    boolean isBeingRushed = false;
    HitchHike currentReq = null;
    public boolean taxiFail = false;

    public DeliveryDroneControllerMk2(RobotController rc) {
        this.random.setSeed(rc.getID());
        int born = rc.getRoundNum();

        // Do heavy computation stuff here as 10 rounds are spent being built
        containsWater = new Boolean[rc.getMapHeight()][rc.getMapWidth()];
        queue = new RingQueue<>(rc.getMapHeight() * rc.getMapWidth());

        elevationHeight = new Integer[rc.getMapHeight()][rc.getMapWidth()];
        searchedForSoupCluster = new boolean[rc.getMapHeight()][rc.getMapWidth()];
        buildMap = new Integer[rc.getMapHeight()][rc.getMapWidth()];
        visited = new boolean[rc.getMapHeight()][rc.getMapWidth()];
        soupCount = new Integer[rc.getMapHeight()][rc.getMapWidth()];

        compressedHeight = rc.getMapHeight() / PlayerConstants.GRID_BLOCK_SIZE + ((rc.getMapHeight() % PlayerConstants.GRID_BLOCK_SIZE == 0) ? 0 : 1);
        compressedWidth = rc.getMapWidth() / PlayerConstants.GRID_BLOCK_SIZE + ((rc.getMapWidth() % PlayerConstants.GRID_BLOCK_SIZE == 0) ? 0 : 1);
        seenBlocks = new boolean[compressedHeight][compressedWidth];



        System.out.println("Getting info");
        getInfo(rc);

        try {
            while (born + 9 >= rc.getRoundNum()) readBlocks();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

        if (allyHQ != null) {
            if (rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rc.getTeam().opponent()).length > 0 &&
                    getChebyshevDistance(rc.getLocation(), allyHQ) <= 5)
                isBeingRushed = true;
        }
    }

    public void searchSurroundingsContinued() throws GameActionException {
        for (int dx = -4; dx <= 4; ++dx) {
            for (int dy = -4; dy <= 4; ++dy) {
                MapLocation delta = new MapLocation(dx, dy);
                MapLocation sensePos = new MapLocation(
                        rc.getLocation().x + dx,
                        rc.getLocation().y + dy);

                if (!rc.canSenseLocation(sensePos)) continue;

                containsWater[sensePos.y][sensePos.x] = rc.senseFlooding(sensePos);
                soupCount[sensePos.y][sensePos.x] = rc.senseSoup(sensePos);
                elevationHeight[sensePos.y][sensePos.x] = rc.senseElevation(sensePos);
            }
        }
    }

    public void run() throws GameActionException {
        if (!rc.isReady()) {
            searchSurroundingsContinued();
            return;
        }

        hqInfo(); // includes scanning robots
        scanNetGuns();
        solveGhostHq();

        communicationHandler.solveEnemyHQLocWithGhosts();

        System.out.println("a6");

//        System.out.println("sensor radius1 "+rc.getCurrentSensorRadiusSquared());

        if (currentState != State.TAXI2) {
            assignRole();
        } else {
            if (!rc.isCurrentlyHoldingUnit()) {
                assignRole();
            }
        }
        System.out.println("I am a " + currentState + " " + sudoku);

        if (currentState == State.TAXI && currentReq != null) {
            // Like this cuz we don't want to execKill on our own miners
            execTaxi();
//            if (rc.getRoundNum() >= ELEVATE_TIME) {
//                currentState = State.DEFENDLATEGAME;
//            } else {
//                execTaxi();
//                return;
//            }
            return;
        }

        if (!rc.isCurrentlyHoldingUnit()) {
            switch (currentState) {
                case ATTACK:  execAttackPatrol();           break;
                case DEFEND:  execDefendLateGame();           break;
                case WANDER:  execWanderPatrol();           break;
                case EXPLORE: execExplore();                break;
                case TAXI: execTaxi();                      break;
                case ATTACKLATEGAME: execAttackLateGame();  break;
                case DEFENDLATEGAME: execDefendLateGame();  break;
                case WANDERLATE: execWanderPatrol2();        break; // picking up ally landscapers
            }
        } else {
            switch (currentState) {
                case TAXI:  execTaxi();           break;
                case TAXI2:  execTaxi2();           break;
                case WANDERLATE:  execWanderPatrol2();           break;
                case STUCKKILL: execKill2();                break;
                case ATTACKLATEGAME: execAttackLateGame();  break;
                default: execKill();
            }
        }
        readBlocks();
    }

    public void assignRole() throws GameActionException {

        /*
            Role assignment depending on turn. Early game defend, late game attack.
         */

        if (currentState == State.ATTACK && rc.getRoundNum() > 1900 && enemyHQ != null && !defendLateGameShield) {
            currentState = State.ATTACKLATEGAME;
            return;
        }

        if (currentState == State.WANDERLATE
//                && rc.isCurrentlyHoldingUnit()
                && rc.getRoundNum() >= 1900) {
            currentState = State.ATTACKLATEGAME;
            return;
        }

        if (defendLateGameShield) {
            return;
        }

        if (currentReq != null && rc.getRoundNum() < 1400) {
            currentState = State.TAXI;
            return;
        }

//        if (currentState == State.WANDERLATE) {
//            return;
//        }

        if (isBeingRushed && rc.getRoundNum() < 500) {
            if (rc.getID() % 2 == 0) {
                currentState = State.DEFEND;
                return;
            }
        }

        System.out.println("assigning role, enemy hq is "+enemyHQ);
        if (rc.getRoundNum() > 1900 && enemyHQ != null && !defendLateGameShield) {
            currentState = State.ATTACKLATEGAME;
        } else         if (rc.getRoundNum() > 1600 && enemyHQ != null && !defendLateGameShield) {
            currentState = State.WANDERLATE;
        } else if (rc.getRoundNum() > 1400 && landscapersOnWall == 8) {
            currentState = State.DEFENDLATEGAME;
        } else {
            currentState = State.DEFEND;
//            switchToDefenceMode();
//            // leave half to defend
//            if (rc.getRoundNum() >= SWITCH_TO_ATTACK && rc.getID() % 2 == 0) {
//                switchToAttackMode();
//            } else {
//                switchToDefenceMode();
////            switchToWanderMode();
            if (currentReq != null) {
                execTaxi();
                return;
            }

            // Half drones explore
            // slowly turn back into other modes
             currentState = State.WANDER;
//            if (!hasExplored) {
//                currentState = State.EXPLORE;
//                hasExplored = true;
//            } else {
//                currentState = State.DEFEND;
//            }
//
//            if (rc.getID() % 1 == 0) {
//                if (!hasExplored) {
//                    currentState = State.EXPLORE;
//                    hasExplored = true;
//                } else {
//                    currentState = State.WANDER;
//                }
//            }
        }
    }


    public void execDefendPatrol() throws GameActionException {

        /*
            Stay in perimeter around home base and pick up nearby enemy units.
            Should not try to chase enemies to pick them up because of cooldowns
         */

        // trying to pick up enemies

        boolean enemyF = false;
        for (RobotInfo enemy : enemies) {
            if (enemy.type == RobotType.LANDSCAPER || enemy.type == RobotType.MINER) {
                if (tryPickUpUnit(enemy)) return;
                if (allyHQ != null && enemy.getLocation().isAdjacentTo(allyHQ)) {
                    enemyF = true;
                    tryMove(movementSolver.directionToGoal(enemy.getLocation()));
                }
            }
        }

        if (allyHQ == null) {
            currentState = State.WANDER;
            run();
            return;
        }

        if (enemyF) return;

        // camp around home
        if (ADJACENT_DEFEND ?
                isAdjacentTo(allyHQ) :
                !rc.getLocation().isWithinDistanceSquared(allyHQ, DEFENSE_RADIUS)) {
            tryMove(movementSolver.directionToGoal(allyHQ));
            System.out.println("move to home");
        } else if (rc.getLocation().isWithinDistanceSquared(allyHQ, DEFENSE_CAMP)) {
            tryMove(movementSolver.directionFromPoint(allyHQ));
            System.out.println("move away from home");
        } else {
            System.out.println("camp outside home");
            assignRole();
        }
    }

    public void execAttackPatrol() throws GameActionException {

        /*
            Stay in perimeter around enemy base and pick up nearby enemy units.
            Should not try to chase enemies to pick them up because of cooldowns
         */

        // trying to pick up enemies
        for (RobotInfo enemy : enemies) {
            if (enemy.type == RobotType.LANDSCAPER || enemy.type == RobotType.MINER) {
                if (tryPickUpUnit(enemy)) return;
            }
        }

        // camp outside enemy hq
        if (enemyHQ != null) {
            if (rc.getLocation().isWithinDistanceSquared(enemyHQ, NET_GUN_RADIUS)) {
                System.out.println("moving away from enemy hq at "+enemyHQ);
                tryMove(enemyHQ.directionTo(rc.getLocation()));
            } else if (rc.getLocation().isWithinDistanceSquared(enemyHQ, CAMPING_RADIUS)) {
                System.out.println("camping outside enemy hq");
                if (rc.getRoundNum() > 1800) {
                    currentState = DeliveryDroneControllerMk2.State.ATTACKLATEGAME;
                }
                readBlocks();
            } else {
                System.out.println("moving directly to enemy hq");
                tryMove(movementSolver.droneDirectionToGoal(enemyHQ));
            }
        } else if (allyHQ != null) { // move away from own hq
            if (movementSolver.nearEdge()) {
                favourableDirection = favourableDirection.opposite();
            }
            spawnBaseDirFrom = favourableDirection;
            movementSolver.windowsRoam();
            System.out.println("moving away from home hq");
        } else {
            switchToWanderMode();
        }
    }

    public void execWanderPatrol() throws GameActionException {
        scanRobots();
        for (RobotInfo enemy : enemies) {
            if (tryPickUpUnit(enemy)) {
                return;
            }
        }
//        killCow();
        searchSurroundingsSoup();
        movementSolver.windowsRoam();
    }

    MapLocation landSloc = null;
    public void execWanderPatrol2() throws GameActionException {
        if (landSloc != null) {
            if (rc.canSenseLocation(landSloc) &&
                    (rc.senseRobotAtLocation(landSloc) == null ||
                            rc.senseRobotAtLocation(landSloc).getTeam() != ALLY)) {
                landSloc = null;
            }

            if (rc.getLocation().isAdjacentTo(landSloc)) {
                if (rc.senseRobotAtLocation(landSloc) != null && rc.senseRobotAtLocation(landSloc).getType() == RobotType.LANDSCAPER) {
                    tryPickUpUnit(rc.senseRobotAtLocation(landSloc));
                } else {
                    landSloc = null;
                }
            }
        }

        if (!rc.isCurrentlyHoldingUnit() && landSloc == null) {
            for (RobotInfo robotInfo : allies) {
                if (robotInfo.getType() == RobotType.LANDSCAPER) {
                    if (!robotInfo.getLocation().isWithinDistanceSquared(allyHQ, 8)) {
                        landSloc = robotInfo.getLocation();
                    }
                }
            }
        }

        if (landSloc == null) {
            movementSolver.windowsRoam();
        } else {
            tryMove(movementSolver.directionToGoal(landSloc));
        }
    }

    public void execDefendLateGame() throws GameActionException {
        // defend / attack code are awfully similar
        for (RobotInfo enemy : rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rc.getTeam().opponent())) {
            if (enemy.type == RobotType.LANDSCAPER && rc.canPickUpUnit(enemy.getID())) {
                rc.pickUpUnit(enemy.getID());
                return;
            }
        }

        MapLocation mapLocation = rc.getLocation();

        if (!mapLocation.isWithinDistanceSquared(allyHQ, 8)) {
            tryMove(movementSolver.directionToGoal(allyHQ));
        } else {
            System.out.println("defend late game stay still");
            defendLateGameShield = true;
            if (rc.getLocation().isAdjacentTo(allyHQ)) {
                tryMove(movementSolver.directionFromPoint(allyHQ));
            }
        }
    }

    public int turnReceiveSudoku = 0;
    public int WAIT_DUMP = 250;
    public int WAIT_KILL = 200; // in case map is very large
    boolean enemyPickUp = false;
    public void execAttackLateGame() throws GameActionException {
        if (enemyHQ == null) {
            currentState = State.DEFENDLATEGAME;
            execDefendLateGame();
        }
        MapLocation mapLocation = rc.getLocation();

        scanRobots();
        if (!rc.isCurrentlyHoldingUnit()) {
            for (RobotInfo enemy : enemies) {
                if (enemy.type == RobotType.LANDSCAPER && rc.canPickUpUnit(enemy.getID()) && enemy.getLocation().isWithinDistanceSquared(enemyHQ, NET_GUN_RANGE)) {
                    rc.pickUpUnit(enemy.getID());
                    enemyPickUp = true;
                    System.out.println("pick up enemy 411");
                    return;
                }
            }
        } else {
            if (!enemyPickUp) {
                for (Direction direction : directions) {
                    if (rc.canSenseLocation(mapLocation.add(direction)) && rc.senseRobotAtLocation(mapLocation.add(direction)) == null
                            && rc.canDropUnit(direction) && mapLocation.add(direction).isAdjacentTo(enemyHQ) && !rc.senseFlooding(mapLocation.add(direction))) {
                        rc.dropUnit(direction);
                    }
                }

                if (mapLocation.isWithinDistanceSquared(enemyHQ, 8)) {
                    // if cant drop next to enemy HQ
                    for (Direction direction : directions) {
                        if (rc.canSenseLocation(mapLocation.add(direction)) && rc.senseRobotAtLocation(mapLocation.add(direction)) == null
                                && rc.canDropUnit(direction) && !rc.senseFlooding(mapLocation.add(direction))) {
                            rc.dropUnit(direction);
                        }
                    }
                }
            } else {
                for (Direction direction : directions) {
                    if (rc.canSenseLocation(mapLocation.add(direction)) && rc.senseFlooding(mapLocation.add(direction))
                            && rc.canDropUnit(direction)) {
                        rc.dropUnit(direction);
                    }
                }
            }
        }



        communicationHandler.receiveSudoku();

        if (sudoku) {
            System.out.println("sudoku to " + enemyHQ);
            if (
                    (rc.isCurrentlyHoldingUnit() && rc.getRoundNum() > Math.max(1900 + WAIT_DUMP, turnReceiveSudoku + WAIT_DUMP))
            ||
                    (!rc.isCurrentlyHoldingUnit() && rc.getRoundNum() > Math.max(1900 + WAIT_KILL, turnReceiveSudoku + WAIT_KILL))
            ) {
                tryMove(movementSolver.directionToGoal(enemyHQ, false));
            } else {
                camp();
            }

        } else {

            camp();
        }
    }

    public void camp() throws GameActionException {
        if (rc.isCurrentlyHoldingUnit()) {
            int carriers = 0;
            scanRobots();

            for (RobotInfo ally : allies) {
                if (ally.getType() == RobotType.DELIVERY_DRONE && ally.isCurrentlyHoldingUnit()) {
                    carriers++;
                }
            }
            System.out.println(carriers);
            if (carriers > 3 && random.nextInt(2) == 0 && rc.isCurrentlyHoldingUnit()
            && rc.getRoundNum() < Math.max(1900 + WAIT_KILL, turnReceiveSudoku + WAIT_KILL)) {
                for (Direction direction : directions) {
                    if (rc.canDropUnit(direction)) {
                        rc.dropUnit(direction);
                        System.out.println("droppppped");
                        break;
                    }
                }
            }
        }
        if (enemyHQ == null) {
            currentState = State.WANDERLATE;
        }
        if (!rc.getLocation().isWithinDistanceSquared(enemyHQ, OUTSIDE_NET_GUN_RANGE)) {
            tryMove(movementSolver.directionToGoal(enemyHQ, true));
//            communicationHandler.wallKill();
        } else {
            if (!campMessageSent) {
                communicationHandler.sendPLUSONE();
                campMessageSent = true;
            }
            System.out.println("waiting for more friends before die");
        }
    }

    public void switchToAttackMode() throws GameActionException {
        currentState = State.ATTACK;
    }

    public void switchToDefenceMode() throws GameActionException {
        currentState = State.DEFEND;
    }

    public void switchToWanderMode() throws GameActionException {
        currentState = State.WANDER;
    }

    public void execKill() throws GameActionException {
        System.out.println("executing kill");
        MapLocation loc = rc.getLocation();
        MapLocation kill;
        for (Direction direction : getDirections()) {
//            System.out.println("trying to kill in direction "+direction+" at "+loc);
            kill = loc.add(direction);
            if (rc.canSenseLocation(kill) && rc.senseFlooding(kill)) {
                System.out.println("water at "+kill);
                if (!rc.isReady()) Clock.yield();
                if (rc.canDropUnit(direction)) {
                    rc.dropUnit(direction);
                    System.out.println("drop unit 482");
                    return;
                } else {
                    System.out.println(rc.canDropUnit(direction));
                }
            } else {
                System.out.println("no water at "+kill);
            }
        }

        nearestWaterTile = getNearestWaterTile2();
        if (nearestWaterTile == null) movementSolver.windowsRoam();
        else if (!isAdjacentTo(nearestWaterTile)) {
            System.out.println("Moving to water tile");
            tryMove(movementSolver.droneDirectionToGoal(nearestWaterTile));
        } else {
            System.out.println("dropping in water tile");
            if (rc.canDropUnit(rc.getLocation().directionTo(nearestWaterTile))) {
                rc.dropUnit(rc.getLocation().directionTo(nearestWaterTile));
                System.out.println("drop unit 502");
                nearestWaterTile = null; // look for different water tile next time
            }
        }

//        if (nearestWaterTile == null) {
//            System.out.println("Looking for water tile");
//            movementSolver.windowsRoam();
//            nearestWaterTile = getNearestWaterTile2();
//        } else {
//            if (!isAdjacentTo(nearestWaterTile)) {
//                System.out.println("Moving to water tile");
//                tryMove(movementSolver.droneDirectionToGoal(nearestWaterTile));
//            } else {
//                System.out.println("dropping in water tile");
//                if (rc.canDropUnit(rc.getLocation().directionTo(nearestWaterTile))) {
//                    rc.dropUnit(rc.getLocation().directionTo(nearestWaterTile));
//                    nearestWaterTile = null; // look for different water tile next time
//                }
//            }
//        }
    }
    public void execKill2() throws GameActionException {
        MapLocation loc = rc.getLocation();
        MapLocation kill;
        for (Direction direction : getDirections()) {
            kill = loc.add(direction);
            if (rc.canSenseLocation(kill) && rc.senseFlooding(kill)) {
                if (!rc.isReady()) Clock.yield();
                if (rc.canDropUnit(direction)) {
                    rc.dropUnit(direction);
                    System.out.println("drop unit 532");
                    currentState = State.WANDERLATE;
                    return;
                }
            }
        }

        movementSolver.windowsRoam();
    }

    public boolean tryPickUpUnit(RobotInfo enemy) throws GameActionException {
        if (rc.canPickUpUnit(enemy.getID())) {
            rc.pickUpUnit(enemy.getID());
            System.out.println("picked up a "+enemy.getType());
            return true;
        }
        return false;
    }

    public SoupCluster determineCluster(MapLocation pos) throws GameActionException {
        /*
            Performs BFS to determine size of cluster
        */
        System.out.println("determining cluster at " + pos);
        if (searchedForSoupCluster[pos.y][pos.x]) return null;

        RingQueue<MapLocation> queue = new RingQueue<>(this.rc.getMapHeight() * this.rc.getMapWidth());
        queue.add(pos);
        searchedForSoupCluster[pos.y][pos.x] = true;

        int crudeSoup = 0;
        int size = 0;
        boolean containsWaterSoup = false;
        int waterSize = 0;

        int x1 = pos.x;
        int x2 = pos.x;
        int y1 = pos.y;
        int y2 = pos.y;

        boolean occupiedByEnemy = false;
        int totalElevation = 0;

        // Incase the enemy has already occupied this spot
        MapLocation refineryPos = null;

        while (!queue.isEmpty() && (x2 - x1) * (y2 - y1) <= 50) {
            hqInfo(); // includes scanning robots
            scanNetGuns();
            solveGhostHq();
            communicationHandler.solveEnemyHQLocWithGhosts();
//            System.out.println("sensor radius "+rc.getCurrentSensorRadiusSquared());

            MapLocation current = queue.poll();
//            System.out.println("Inspecting " + current + " : " + ((containsWater[current.y][current.x] != null &&
//                    containsWater[current.y][current.x]) ? 1 : 0));
            if (buildMap[current.y][current.x] != null && buildMap[current.y][current.x] == RobotType.REFINERY.ordinal()) {
                refineryPos = current;
            }

            searchedForSoupCluster[current.y][current.x] = true;
            occupiedByEnemy |= rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rc.getTeam().opponent()).length > 0;

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


            for (Direction delta : getDirections()) {
                MapLocation neighbour = current.add(delta);
//                System.out.println("testing: " + neighbour + " " + searchedForSoupCluster[neighbour.y][neighbour.x]);
                if (!rc.onTheMap(neighbour)) continue;
                if (searchedForSoupCluster[neighbour.y][neighbour.x]) continue;

                // If you cant tell whether neighbour has soup or not move closer to it

                while (!rc.isReady()) Clock.yield();



                int usedMoves = 0; // remove infinite loops
                while (soupCount[neighbour.y][neighbour.x] == null) {
                    if (rc.canSenseLocation(neighbour) && rc.sensePollution(neighbour) > DRONE_POLLUTION_THRESHOLD) break;
                    if (usedMoves > 10) {
                        usedMoves = -1; // lazy flag
                        break;
                    }
                    if (tryMove(movementSolver.droneDirectionToGoal(neighbour))) {
                        ++usedMoves;
                        searchSurroundingsContinued();
                        readBlocks();

                        // Only do nothing if you need to make another move
                        // if (soupCount[neighbour.y][neighbour.x] == null) Clock.yield();
                    }
                }
                if (usedMoves < 0) continue;

                crudeSoup += (soupCount[neighbour.y][neighbour.x] == null) ? 0 : soupCount[neighbour.y][neighbour.x];
                containsWaterSoup |= (containsWater[neighbour.y][neighbour.x] != null &&
                        containsWater[neighbour.y][neighbour.x]);



//                if (Math.abs(elevationHeight[neighbour.y][neighbour.x] - elevationHeight[current.y][current.x]) > 3) continue;

                if (soupCount[neighbour.y][neighbour.x] > 0 || (buildMap[neighbour.y][neighbour.x] != null && buildMap[neighbour.y][neighbour.x] == RobotType.REFINERY.ordinal())) {
                    queue.add(neighbour);
                    searchedForSoupCluster[neighbour.y][neighbour.x] = true;
                }
            }
        }

        if (occupiedByEnemy && size < PlayerConstants.CONTEST_SOUP_CLUSTER_SIZE) return null;

        SoupCluster found = new SoupCluster(x1, y1, x2, y2, size, crudeSoup, waterSize, totalElevation / size);

//        System.out.println("Finished finding cluster: " + found.size);

        // Check to see if other miners have already found this cluster
        boolean hasBeenBroadCasted = false;
        for (SoupCluster soupCluster : soupClusters) {
            if (found.inside(soupCluster)) hasBeenBroadCasted = true;
        }

        if (hasBeenBroadCasted) return null;
        return found;
    }

    public SoupCluster searchSurroundingsSoup() throws GameActionException {
        // Check to see if you can detect any soup
        for (int dx = -4; dx <= 4; ++dx) {
            for (int dy = -4; dy <= 4; ++dy) {
                MapLocation sensePos = new MapLocation(rc.getLocation().x + dx, rc.getLocation().y + dy);
                if (!rc.canSenseLocation(sensePos)) continue;


                if (soupCount[sensePos.y][sensePos.x] != null) continue;
                containsWater[sensePos.y][sensePos.x] = rc.senseFlooding(sensePos);
                elevationHeight[sensePos.y][sensePos.x] = rc.senseElevation(sensePos);

                // Check soup
                int crudeAmount = rc.senseSoup(sensePos);

                if (soupCount[sensePos.y][sensePos.x] != null) {
                    // Update soup value but dont search for a cluster
                    soupCount[sensePos.y][sensePos.x] = crudeAmount;
                    continue;
                }

                soupCount[sensePos.y][sensePos.x] = crudeAmount;

                if (rc.canSenseLocation(sensePos) &&
                        !searchedForSoupCluster[sensePos.y][sensePos.x] && crudeAmount > 0) {
                    SoupCluster foundSoupCluster = determineCluster(sensePos);

                    if (foundSoupCluster == null) break;
                    System.out.println("Found cluster: " + foundSoupCluster.toStringPos() + " " + foundSoupCluster.size + " " + foundSoupCluster.waterSize);
                    soupClusters.add(foundSoupCluster);
//                    currentState = State.DEFEND;
                    communicationHandler.sendCluster(foundSoupCluster);
                    // If there are no clusters for team miners, have be a taxi
                    if (foundSoupCluster.elevation > GameConstants.getWaterLevel(rc.getRoundNum() + 300)) {
                        // miners will want to taxi here
                        currentState = State.WANDER;
                    }
                    System.out.println(foundSoupCluster.elevation + " " + GameConstants.getWaterLevel(rc.getRoundNum() + 100) + " " + currentState);
                    return foundSoupCluster;
                }
            }
        }
        return null;
    }

    public void killCow() throws GameActionException {

        while (rc.sensePollution(rc.getLocation()) < DRONE_POLLUTION_THRESHOLD) {
            if (rc.isCurrentlyHoldingUnit()) {
                execKill();
            } else {
                RobotInfo[] cows = rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), NEUTRAL);
                if (cows.length == 0) return;

                MapLocation best = cows[0].getLocation();
                int closest = 100;
                for (RobotInfo cow : cows) {
                    if (getChebyshevDistance(rc.getLocation(), cow.getLocation()) < closest) {
                        best = cow.getLocation();
                        closest = getChebyshevDistance(rc.getLocation(), cow.getLocation());
                    }
                }

                tryMove(movementSolver.directionToGoal(best));
                readBlocks();
//                Clock.yield();

                for (RobotInfo cow : cows) {
                    if (rc.canPickUpUnit(cow.getID())) {
                        rc.pickUpUnit(cow.getID());
                        break;
                    }
                }
            }
        }
    }

    public void execExplore() throws GameActionException {
        Stack<MapLocation> stack = new Stack<>();
        stack.push(rc.getLocation());

        LinkedList<MapLocation> visitedBlocks = new LinkedList<>();

        System.out.println("type now: " + currentState);
        while (true) {
            if (rc.getRoundNum() > 1000) {
                currentState = State.DEFENDLATEGAME;
                execDefendLateGame();
                break;
            }

            if (stack.isEmpty()) {
                LinkedList<MapLocation> starts = new LinkedList<>();
                System.out.println("finding start point");
                System.out.println(compressedHeight);
                System.out.println(compressedWidth);
                for (int y = 0; y < compressedHeight; ++y) {
                    for (int x = 0; x < compressedWidth; ++x) {
                        if (seenBlocks[y][x]) continue;
                        starts.add(new MapLocation(x, y));
                    }
                }
                if (starts.size() == 0) {
                    break;
                }
                int index = (Math.abs(random.nextInt())) % starts.size();
                stack = new Stack<>();
                stack.push(new MapLocation(starts.get(index).x * BLOCK_SIZE, starts.get(index).y * BLOCK_SIZE));
            }

            MapLocation node = null;
            for (int i = 0; i < stack.size(); ++i) {
                if (getChebyshevDistance(rc.getLocation(), stack.peek()) <= 10) {
                    node = stack.pop();
                    break;
                }
                stack.push(stack.pop());
            }
            if (node == null) node = stack.pop();

            // Kill annoying cows
//            killCow();
            solveGhostHq();

            if (visited[node.y][node.x]) {
                continue;

            }

            // TODO: deal with cows (kill if close to base, ignore if close to enemy)

            System.out.println("searching: " + node);

            boolean isProtectedByNetGun = false;
            for (int i = 0; i < 2 * Math.max(rc.getMapHeight(), rc.getMapWidth()) &&
                    (soupCount[node.y][node.x] == null || visited[rc.getLocation().y][rc.getLocation().x]); ) {

//                killCow();

                for (RobotInfo enemies : rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rc.getTeam().opponent())) {
                    if ((enemies.type == RobotType.NET_GUN || enemies.type == RobotType.HQ) &&
                            getDistanceSquared(node, enemies.getLocation()) <= NET_GUN_RADIUS) {
                        isProtectedByNetGun = true;
                        break;
                    }
                }

                rc.setIndicatorDot(node, 255, 0, 0);
                if (tryMove(movementSolver.directionToGoal(node))) {
                    solveGhostHq();
                    searchSurroundingsSoup();
                    readBlocks();
                    if (currentState != State.EXPLORE) return;
                    ++i;
//                    Clock.yield();
                }
            }

            if (isProtectedByNetGun) continue;

            // Broadcast what areas have been searched
            if (!seenBlocks[node.y / BLOCK_SIZE][node.x / BLOCK_SIZE]) {
                visitedBlocks.add(new MapLocation(node.x / BLOCK_SIZE, node.y / BLOCK_SIZE));
                seenBlocks[node.y / BLOCK_SIZE][node.x / BLOCK_SIZE] = true;

                if (visitedBlocks.size() == 4) {
                    communicationHandler.sendMapBlocks(visitedBlocks.toArray(new MapLocation[0]));
                    System.out.println("Sending " + visitedBlocks.size());
                    visitedBlocks = new LinkedList<>();
                }
            }

            if (visited[node.y][node.x]) continue;
            visited[node.y][node.x] = true;

            for (Direction dir : getDirections()) {
                MapLocation nnode = node.add(dir).add(dir);
                if (!onTheMap(nnode) || visited[nnode.y][nnode.x]) continue;
                stack.push(nnode);
            }
        }
        if (visitedBlocks.size() > 0) {
            communicationHandler.sendMapBlocks(visitedBlocks.toArray(new MapLocation[0]));
        }
        currentState = State.DEFEND;
    }

    public void execTaxi() throws GameActionException {

        // Deal with landscaper being placed when travelling
        int landScapers = landscapersOnWall;
        if (landScapers == 8 && currentReq.goal.equals(allyHQ)) {
            currentReq.goal = currentReq.pos;
        }

        if (rc.isCurrentlyHoldingUnit()) {
//            System.out.println("Here: " + currentReq.pos + " "  + currentReq.goal);
            for (RobotInfo robot : rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rc.getTeam().opponent())) {
                if (robot.type == RobotType.NET_GUN) {
                    if (getDistanceSquared(currentReq.goal, robot.getLocation()) < 24) {
                        // There is a netgun in the way rip
                        for (Direction dir : getDirections()) {
                            if (rc.canDropUnit(dir) && !rc.senseFlooding(rc.getLocation().add(dir))) {
                                rc.dropUnit(dir);
                                currentReq = null;
                                currentState = State.WANDER;
                                break;
                            }
                        }
                        currentState = State.TAXI2;
//                        // Hopefully doesn't get here (kill miner). unfortunately it does sometimes get here
//                        rc.dropUnit(Direction.CENTER);
                    }
                }
            }

            boolean getCloser = (currentReq.goal.equals(allyHQ) && rc.getRoundNum() > 500) ?
                    getChebyshevDistance(rc.getLocation(), currentReq.goal) > 2 : !rc.getLocation().isAdjacentTo(currentReq.goal);
            if (getCloser) {
//                System.out.println(1);
                tryMove(movementSolver.directionToGoal(currentReq.goal));
                // Deal with landscaper being placed when travelling
            } else {
//                System.out.println(2);
                Direction direction = rc.getLocation().directionTo(currentReq.goal);
                if (rc.canDropUnit(direction)
                        && rc.canSenseLocation(rc.getLocation().add(direction))
                        && !rc.senseFlooding(rc.getLocation().add(direction))
                ) {
                    rc.dropUnit(direction);
                    currentReq = null;
                    currentState = State.WANDER;
//                    /assignRole();
                    return;
                }

//                System.out.println(1);
//                System.out.println(3);
                // Wait till spot is free again (normally a unit moves)
                RobotInfo used = rc.senseRobotAtLocation(currentReq.goal);
                if (used != null && (used.getType() == RobotType.MINER ||
                        used.getType() == RobotType.LANDSCAPER ||
                        used.getType() == RobotType.DELIVERY_DRONE)) {

                    int start = rc.getRoundNum();
                    while (rc.getRoundNum() <= start + 10) readBlocks();

                    if (rc.canDropUnit(direction)) {
                        rc.dropUnit(direction);
                        currentReq = null;
                        currentState = State.WANDER;
                        return;
                    }
                }

//                System.out.println(2);
//                System.out.println(4);
                // Place on surrounding tiles
                for (Direction dir : getDirections()) {
                    if (dir == Direction.CENTER) continue;
                    MapLocation target = currentReq.goal.add(dir);
                    if (rc.canSenseLocation(target) && rc.senseFlooding(target)) continue;
                    while (!isAdjacentTo(target) && movementSolver.moves < 10) {
                        while (!rc.isReady()) readBlocks();
                        tryMove(movementSolver.directionToGoal(target));
                        readBlocks();
                    }
                    if (!isAdjacentTo(target)) continue;
                    while (!rc.isReady()) readBlocks();
                    if (rc.canSenseLocation(target) && rc.senseFlooding(target)) continue;
                    if (rc.canDropUnit(rc.getLocation().directionTo(target))) {
                        rc.dropUnit(rc.getLocation().directionTo(target));
                        currentReq = null;
                        currentState = State.WANDER;
                        return;
                    }
                }
                System.out.println(5);
                // Wow we cant catch a break
                currentState = State.TAXI2;
                return;

                /*
                // TODO: deal with edge of world
                if (rc.senseFlooding(currentReq.goal)) {
                    Direction dir = rc.getLocation().directionTo(currentReq.goal);
                    drop :
                    {
                        while (true) {
                            tryMove(movementSolver.directionToGoal(rc.getLocation().add(dir)));
                            while (!rc.isReady()) Clock.yield();
                            for (Direction d : getDirections()) {
                                if (rc.canDropUnit(d) && !rc.senseFlooding(rc.getLocation().add(d))) {
                                    rc.dropUnit(d);
                                    break drop;
                                }
                            }
                        }
                    }
                }
                if (rc.canDropUnit(rc.getLocation().directionTo(currentReq.goal))) {
                    rc.dropUnit(rc.getLocation().directionTo(currentReq.goal));
                } else {

                }

                 */
            }
        } else {
            if (rc.getRoundNum() > 1400) {
                currentState = State.WANDERLATE;
            }
            if (rc.canSenseRobot(currentReq.reqID)) {
                if (!rc.canPickUpUnit(currentReq.reqID)) {
                    tryMove(movementSolver.directionToGoal(rc.senseRobot(currentReq.reqID).getLocation()));
                } else {
                    rc.pickUpUnit(currentReq.reqID);
                }
            } else {
                if (isAdjacentTo(currentReq.pos)) {
                    currentReq = null;
                    currentState = State.WANDER;
                    return;
                } else {
                    tryMove(movementSolver.directionToGoal(currentReq.pos));
                }
            }
        }
    }

    public void execTaxi2() throws GameActionException {
        System.out.println("Here in taxi 2");
        if (!rc.isCurrentlyHoldingUnit()) {
            assignRole();
            return;
        }

        for (Direction dir : getDirections()) {
            if (rc.canDropUnit(dir) && !rc.senseFlooding(rc.getLocation().add(dir))) {
                rc.dropUnit(dir);
                currentReq = null;
                currentState = State.WANDER;
                assignRole();
                break;
            }
        }
        movementSolver.windowsRoam();
    }

    int lastPos = 1;
    public void readBlocks() throws GameActionException {
        while (lastPos < rc.getRoundNum() && Clock.getBytecodesLeft() > 500) {
            for (Transaction t : rc.getBlock(lastPos)) {
                int[] message = t.getMessage();
                switch (communicationHandler.identify(message)) {
                    case CLUSTER: processCluster(message); break;
                    case HITCHHIKE_REQUEST: processReq(message, lastPos); break;
                    case HITCHHIKE_ACK: processReqAck(message); break;
                    case MAPBLOCKS: processMapBlocks(message); break;
                    case LANDSCAPERS_ON_WALL: processLandscapersOnWall(message); break;
                    case ALLYHQ: processAllyHQLoc(message); break;
                    case ENEMYHQ: processEnemyHQLoc(message); break;
                    case NET_GUN_LOCATIONS: processNetGunLoc(message); break;
                    case NET_GUN_DIE: processNetGunDie(message); break;
                    case ALL_ATTACK: processAllAttack(message, lastPos); break;
                }
            }
            ++lastPos;
        }
    }

    public void processCluster(int[] message) {
        SoupCluster broadcastedSoupCluster = communicationHandler.getCluster(message);

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
                    visited[y][x] = true;
                }
            }
        }
        soupClusters.removeIf(sc -> sc.size == 0);
    }

    public void processReq(int[] message, int round) throws GameActionException {
        HitchHike r = communicationHandler.getHitchHikeRequest(message);
        r.roundNum = round;
        reqs.add(r);


        if (currentReq == null) {
            System.out.println("finding req: " + reqs.size());
            for (HitchHike req : reqs) {
                if (Math.abs((rc.getRoundNum() - req.roundNum - 1) / 5 - getChebyshevDistance(rc.getLocation(), req.goal) / GRID_BLOCK_SIZE) <= 3) {
                    System.out.println("I'll pick you up: " + req.toString());
                    currentReq = req;
                    currentReq.droneID = rc.getID();
                    communicationHandler.sendHitchHikeAck(req);
                    currentState = State.TAXI;
                }
            }
        }
        reqs.removeIf(re -> (currentReq != null && re.reqID == currentReq.reqID && re.goal.equals(currentReq.goal) && re.pos.equals(currentReq.pos)) ||
                (re.roundNum > 64 / BLOCK_SIZE + 9));
    }

    public void processReqAck(int[] message) throws GameActionException {
        HitchHike ack = communicationHandler.getHitchHikeAck(message);
        if (currentReq != null) {
            // Check to see if another drone has ACK'ed
            //System.out.println(currentReq.toString() + " " + ack.toString());
            if (currentReq.pos.equals(ack.pos) && currentReq.goal.equals(ack.goal)) {
                //System.out.println("I see an ACK " + ack.droneID);
                if (ack.droneID == rc.getID()) {
                    currentReq.confirmed = true;
                } else if (!currentReq.confirmed) {
                    currentReq = null;
                }
            }
        }
        reqs.removeIf(r -> r.pos.equals(currentReq.pos) && r.reqID == ack.reqID && r.goal.equals(ack.goal));
    }

    public void processMapBlocks(int[] message) throws GameActionException {
        MapLocation[] blocks = communicationHandler.getMapBlocks(message);
        for (MapLocation pos : blocks) {
            seenBlocks[pos.y][pos.x] = true;

            if (visited != null) {
                for (int x = pos.x * BLOCK_SIZE; x < Math.min(rc.getMapWidth(), (pos.x + 1) * BLOCK_SIZE); ++x) {
                    for (int y = pos.y * BLOCK_SIZE; y < Math.min(rc.getMapHeight(), (pos.y + 1) * BLOCK_SIZE); ++y) {
                        visited[y][x] = true;
                    }
                }
            }
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

    public void processNetGunLoc(int[] message) throws GameActionException {
        communicationHandler.decode(message);
        MapLocation mapLocation = new MapLocation(message[1], message[2]);

        if (!netGuns.contains(mapLocation)) {
            netGuns.add(mapLocation);
        }
    }

    public void processNetGunDie(int[] message) throws GameActionException {
        communicationHandler.decode(message);
        MapLocation mapLocation = new MapLocation(message[1], message[2]);

        netGuns.remove(mapLocation);
    }

    public void processAllAttack(int[] message, int round) throws GameActionException {
        sudoku = true;
        turnReceiveSudoku = round;
    }
}
