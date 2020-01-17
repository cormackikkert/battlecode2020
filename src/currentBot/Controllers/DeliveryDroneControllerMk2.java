package currentBot.Controllers;

import battlecode.common.*;
import currentBot.*;

import java.util.Deque;
import java.util.LinkedList;
import java.util.Random;
import java.util.Stack;

import static currentBot.Controllers.PlayerConstants.*;

/**
 * New strategy since 14/01/2020:
 * > get soup
 * > landscapers
 * > drone defence
 * > drone attack (muddle up enemy formations)
 */
public class DeliveryDroneControllerMk2 extends Controller {

    enum State {
        DEFEND,
        ATTACK,
        WANDER,
        EXPLORE // Search for soup clustsers in places the miner couldn't reach
    }

    State currentState = null;

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

    HitchHike currentReq = null;

    public DeliveryDroneControllerMk2(RobotController rc) {
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

        updateReqs();
        if (currentReq != null) {
            execTaxi();
            return;
        }

        assignRole();
        hqInfo(); // includes scanning robots

        // if (currentState == State.ATTACK) currentState = State.DEFEND;

        if (!rc.isCurrentlyHoldingUnit()) {
            switch (currentState) {
                case ATTACK:  execAttackPatrol();            break;
                case DEFEND:  execDefendPatrol();            break;
                case WANDER:  execWanderPatrol();            break;
                case EXPLORE: execExplore();                break;
            }
        } else {
            execKill();
        }
    }

    public void assignRole() throws GameActionException {

        /*
            Role assignment depending on turn. Early game defend, late game attack.
         */


        if (rc.getRoundNum() >= SWITCH_TO_ATTACK) {
            switchToAttackMode();
        } else {
//            switchToDefenceMode();
            switchToWanderMode();
        }

        // Half drones explore
        // slowly turn back into other modes
        if (rc.getID() % 2 == 0 && !hasExplored) {
            currentState = State.EXPLORE;
            hasExplored = true;
        }
    }

    public void execDefendPatrol() throws GameActionException {

        /*
            Stay in perimeter around home base and pick up nearby enemy units.
            Should not try to chase enemies to pick them up because of cooldowns
         */

        // trying to pick up enemies
        for (RobotInfo enemy : enemies) {
            if (enemy.type == RobotType.LANDSCAPER || enemy.type == RobotType.MINER) {
                if (tryPickUpUnit(enemy)) return;
            }
        }

        // camp around home
        if (ADJACENT_DEFEND ?
                isAdjacentTo(allyHQ) :
                rc.getLocation().isWithinDistanceSquared(allyHQ, DEFENSE_RADIUS)) {
            System.out.println("stand still to defend");
        } else {
            if (allyHQ != null) {
                if (!tryMove(rc.getLocation().directionTo(allyHQ))) {
                    tryMove(randomDirection());
                }
            } else {
                tryMove(randomDirection()); // should never get here since should find hq
            }
            System.out.println("move to defend");
        }
    }

    public void execAttackPatrol() throws GameActionException {

        /*
            Stay in perimeter around enemy base and pick up nearby enemy units.
            Should not try to chase enemies to pick them up because of cooldowns
         */

        // trying to pick up enemies
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, ENEMY);
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
            } else {
                System.out.println("moving directly to enemy hq");
                tryMove(movementSolver.droneDirectionToGoal(enemyHQ));
            }
        } else if (allyHQ != null) { // move away from own hq
            if (movementSolver.nearEdge()) {
                favourableDirection = favourableDirection.opposite();
            }
            tryMove(movementSolver.directionGo(favourableDirection));
            System.out.println("moving away from home hq");
        } else {
            tryMove(randomDirection());
        }
    }

    public void execWanderPatrol() throws GameActionException {
        for (RobotInfo enemy : enemies) {
            if (tryPickUpUnit(enemy)) {
                return;
            }
        }

        movementSolver.windowsRoam();
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
        if (nearestWaterTile == null) {
            System.out.println("Looking for water tile");
            movementSolver.windowsRoam();
            nearestWaterTile = getNearestWaterTile2();
        }

        if (!isAdjacentTo(nearestWaterTile)) {
            System.out.println("Moving to water tile");
            tryMove(movementSolver.droneDirectionToGoal(nearestWaterTile));
        } else {
            System.out.println("dropping in water tile");
            if (rc.canDropUnit(rc.getLocation().directionTo(nearestWaterTile))) {
                rc.dropUnit(rc.getLocation().directionTo(nearestWaterTile));
                nearestWaterTile = null; // look for different water tile next time
            }
        }
    }

    public boolean tryPickUpUnit(RobotInfo enemy) throws GameActionException {
        if (rc.canPickUpUnit(enemy.getID())) {
            rc.pickUpUnit(enemy.getID());
            System.out.println("picked up a "+enemy.getType());
            return true;
        }
        return false;
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
                                visited[y][x] = true;
                            }
                        }
                    }

                }
            }
        }
        lastRound = rc.getRoundNum(); // Keep track of last round we scanned the block chain
    }

    void updateReqs() throws GameActionException {
        for (int i = lastRoundReqs; i < rc.getRoundNum(); ++i) {
            for (Transaction tx : rc.getBlock(i)) {
                int[] mess = tx.getMessage();
                if (communicationHandler.identify(mess) == CommunicationHandler.CommunicationType.HITCHHIKE_REQUEST) {
                    HitchHike req = communicationHandler.getHitchHikeRequest(mess);
                    req.roundNum = i;
                    reqs.add(req);
                }
                if (communicationHandler.identify(mess) == CommunicationHandler.CommunicationType.HITCHHIKE_ACK) {
                    HitchHike ack = communicationHandler.getHitchHikeAck(mess);
                    if (currentReq != null) {
                        // Check to see if another drone has ACK'ed
                        if (currentReq.pos == ack.pos && currentReq.goal == ack.goal && !currentReq.confirmed) {
                            currentReq = null;
                        } else {
                            currentReq.confirmed = true;
                        }
                    }
                }
            }
        }
        if (currentReq == null) {
            for (HitchHike req : reqs) {
                if (rc.getRoundNum() - req.roundNum + 1 == getChebyshevDistance(rc.getLocation(), req.goal)) {
                    currentReq = req;
                    currentReq.droneID = rc.getID();
                    communicationHandler.sendHitchHikeAck(req);
                }
            }
        }
        lastRoundReqs = rc.getRoundNum(); // Keep track of last round we scanned the block chain
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

        int x1 = pos.x;
        int x2 = pos.x;
        int y1 = pos.y;
        int y2 = pos.y;

        // Incase the enemy has already occupied this spot
        MapLocation refineryPos = null;

        while (!queue.isEmpty() && (x2 - x1) * (y2 - y1) <= 50) {
            MapLocation current = queue.poll();
            System.out.println("Inspecting " + current);
            if (buildMap[current.y][current.x] != null && buildMap[current.y][current.x] == RobotType.REFINERY.ordinal()) {
                refineryPos = current;
            }

            searchedForSoupCluster[current.y][current.x] = true;

            x1 = Math.min(x1, current.x);
            x2 = Math.max(x2, current.x);

            y1 = Math.min(y1, current.y);
            y2 = Math.max(y2, current.y);

            // Determine if we already know about this cluster
            // We keep searching instead of returning to mark each cell as checked
            // so we don't do it again

            for (Direction delta : Direction.allDirections()) {
                MapLocation neighbour = current.add(delta);
                if (!onTheMap(neighbour)) continue;
                if (searchedForSoupCluster[neighbour.y][neighbour.x]) continue;

                // If you cant tell whether neighbour has soup or not move closer to it

                while (!rc.isReady()) Clock.yield();

                int usedMoves = 0; // remove infinite loops
                while (soupCount[neighbour.y][neighbour.x] == null) {
                    if (usedMoves > 10) {
                        usedMoves = -1; // lazy flag
                        break;
                    }
                    if (tryMove(movementSolver.droneDirectionToGoal(neighbour))) {
                        ++usedMoves;
                        searchSurroundingsContinued();

                        // Only do nothing if you need to make another move
                        if (soupCount[neighbour.y][neighbour.x] == null) Clock.yield();
                    }
                }
                if (usedMoves < 0) continue;

                crudeSoup += (soupCount[neighbour.y][neighbour.x] == null) ? 0 : soupCount[neighbour.y][neighbour.x];

                if (Math.abs(elevationHeight[neighbour.y][neighbour.x] - elevationHeight[current.y][current.x]) > 3) continue;

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

    public void killCow() throws GameActionException {
        while (true) {
            RobotInfo[] cows = rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), NEUTRAL);
            if (cows.length == 0) return;
            System.out.println("FOUND COW");
            for (RobotInfo cow : cows) {
                while (!tryPickUpUnit(cow)) {
                    tryMove(movementSolver.directionToGoal(rc.getLocation(), cow.getLocation()));
                    Clock.yield();
                }
                while (rc.isCurrentlyHoldingUnit()) execKill();
            }
        }

    }
    public void execExplore() throws GameActionException {
        updateSeenBlocks();
        updateClusters();

        Stack<MapLocation> stack = new Stack<>();
        stack.push(rc.getLocation());

        LinkedList<MapLocation> visitedBlocks = new LinkedList<>();

        while (!stack.isEmpty()) {
            MapLocation node = stack.pop();

            // Kill annoying cows
            killCow();


            if (visited[node.y][node.x]) {
                updateMapBlocks();
                // Pick random square to start from
                LinkedList<MapLocation> starts = new LinkedList<>();
                for (int y = 0; y < compressedHeight; ++y) {
                    for (int x = 0; x < compressedWidth; ++x) {
                        if (seenBlocks[y][x]) continue;
                        starts.add(new MapLocation(x, y));
                    }
                }
                if (starts.size() == 0) {
                    return;
                }
                int index = (Math.abs(random.nextInt())) % starts.size();
                stack = new Stack<>();
                node = new MapLocation(starts.get(index).x * BLOCK_SIZE, starts.get(index).y * BLOCK_SIZE);
            }

            // TODO: deal with cows (kill if close to base, ignore if close to enemy)

            System.out.println("searching: " + node);
            for (int i = 0; i < 2 * Math.max(rc.getMapHeight(), rc.getMapWidth()) &&
                    (soupCount[node.y][node.x] == null || visited[rc.getLocation().y][rc.getLocation().x]); ) {

                killCow();

                rc.setIndicatorDot(node, 255, 0, 0);
                if (tryMove(movementSolver.directionToGoal(node))) {
                    searchSurroundingsSoup();
                    ++i;
                    Clock.yield();
                }
            }

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

            for (Direction dir : Direction.allDirections()) {
                MapLocation nnode = node.add(dir);
                if (!onTheMap(nnode) || visited[nnode.y][nnode.x]) continue;

                stack.push(nnode);
            }
        }
        if (visitedBlocks.size() > 0) {
            communicationHandler.sendMapBlocks(visitedBlocks.toArray(new MapLocation[0]));
        }
    }

    public void execTaxi() throws GameActionException {
        if (rc.isCurrentlyHoldingUnit()) {
            if (!isAdjacentTo(currentReq.goal)) {
                tryMove(movementSolver.directionToGoal(currentReq.goal));
            } else {
                if (rc.canDropUnit(rc.getLocation().directionTo(currentReq.goal))) {
                    rc.dropUnit(rc.getLocation().directionTo(currentReq.goal));
                } else {
                    for (Direction dir : Direction.allDirections()) {
                        if (rc.canDropUnit(dir)) {
                            rc.dropUnit(dir);
                            break;
                        }
                    }
                }
            }
        } else {
            // System.out.println("gonna pick up " + currentReq.pos);
            if (!isAdjacentTo(currentReq.pos)) {
                tryMove(movementSolver.directionToGoal(currentReq.pos));
            } else {
                RobotInfo robot = rc.senseRobotAtLocation(currentReq.pos);
                if (robot != null && rc.canPickUpUnit(robot.ID)) {
                    rc.pickUpUnit(robot.ID);
                }
            }

        }
    }
}
