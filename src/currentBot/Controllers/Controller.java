package currentBot.Controllers;

/*
    So I thought we would do a controller thing like this:
    https://github.com/j-mao/battlecode-2019/blob/master/newstart/MyRobot.java
*/

import battlecode.common.*;
import currentBot.CommunicationHandler;
import currentBot.MapBlock;
import currentBot.MovementSolver;
import currentBot.RingQueue;

import java.util.LinkedList;
import java.util.List;

public abstract class Controller {
    public RobotController rc = null;

    public Team NEUTRAL = Team.NEUTRAL;
    public Team ALLY;
    public Team ENEMY;

    public int mapX, mapY;

    public MapLocation allyHQ = null; // to be filled out by a blockchain message
    public MapLocation enemyHQ = null;

    public RobotInfo[] enemies;
    public RobotInfo[] allies;

    public CommunicationHandler communicationHandler;
    public MovementSolver movementSolver;

    public MapLocation spawnPoint;         // loc of production
    public MapLocation spawnBase;          // loc of building which produced this unit (relevant for units)
    public Direction spawnBaseDirFrom;     // dir FROM building which produced this unit
    public Direction spawnBaseDirTo;       // dir TO building which produced this unit
    public Direction favourableDirection;  // direction to roam

    public int spawnTurn;

    Boolean[][] containsWater;
    Integer[][] elevationMap;
    Integer[][] soupCount;
    RobotType[][] allyBuildMap;
    RobotType[][] enemyBuildMap;
    boolean[][] visited;

    MapBlock[][] mapBlocks;
    boolean[][] seenBlocks; // Subdivides grid into areas and this stores which areas have been seen

    int lrmb = 1;
    int lrsb = 1;

    public RingQueue<MapLocation> queue;

    enum Symmetry {
        HORIZONTAL,
        VERTICAL,
        ROTATIONAL
    }

    // ghost hq if enemy hq unknown
    public boolean ghostH = true;
    public boolean ghostV = true;
    public boolean ghostR = true;
    public int ghostsKilled = 0;

    Direction[] directions = {
            Direction.NORTH,
            Direction.NORTHEAST,
            Direction.EAST,
            Direction.SOUTHEAST,
            Direction.SOUTH,
            Direction.SOUTHWEST,
            Direction.WEST,
            Direction.NORTHWEST
    };

    Direction[] cardinal = {directions[0], directions[2], directions[4], directions[6]};
    Direction[] ordinal = {directions[1], directions[3], directions[5], directions[7]};

    public void getInfo(RobotController rc) {
        mapX = rc.getMapWidth();
        mapY = rc.getMapHeight();
        ALLY = rc.getTeam();
        ENEMY = rc.getTeam().opponent();
        this.rc = rc;
        this.communicationHandler = new CommunicationHandler(rc, this);
        this.movementSolver = rc.getType() == RobotType.DELIVERY_DRONE ? new MovementSolver(rc, this) : new MovementSolver(rc);
        this.spawnTurn = rc.getRoundNum();
        this.spawnPoint = rc.getLocation();
        getSpawnBase();
        getHQInfo();
    }

    boolean tryBuild(RobotType type, Direction dir) throws GameActionException {
        if (rc.isReady() && rc.canBuildRobot(type, dir)) {
            rc.buildRobot(type, dir);
            return true;
        } else return false;
    }

    boolean tryMine(Direction dir) throws GameActionException {
        if (rc.isReady() && rc.canMineSoup(dir)) {
            rc.mineSoup(dir);
            return true;
        } else return false;
    }

    public boolean tryMove(Direction dir) throws GameActionException {
        // System.out.println("I am trying to move " + dir + "; " + rc.isReady() + " " + rc.getCooldownTurns() + " " + rc.canMove(dir));
        while (!rc.isReady()) Clock.yield();
        if (rc.canMove(dir)) {
            rc.move(dir);
            return true;
        } else return false;
    }

    void tryBlockchain() throws GameActionException {
        int[] message = new int[7];
        for (int i = 0; i < 7; i++) {
            message[i] = 123;
        }
        if (rc.canSubmitTransaction(message, 10))
            rc.submitTransaction(message, 10);
    }

    boolean tryRefine(Direction dir) throws GameActionException {
        if (rc.isReady() && rc.canDepositSoup(dir)) {
            rc.depositSoup(dir, rc.getSoupCarrying());
            return true;
        } else return false;
    }

    public boolean tryShoot(RobotInfo robotInfo) throws GameActionException {
        if (rc.canShootUnit(robotInfo.getID())) {
            rc.shootUnit(robotInfo.getID());
            System.out.println("shot down enemy");
            return true;
        }
        return false;
    }

    Direction randomDirection() {
        return directions[(int) (Math.random() * directions.length)];
    }

    int getChebyshevDistance(MapLocation p1, MapLocation p2) {
        return Math.max(Math.abs(p1.x - p2.x), Math.abs(p1.y - p2.y));
    }

    int getDistanceSquared(MapLocation p1, MapLocation p2) {
        return (p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y);
    }

    int getDistanceFrom(RobotInfo other) {
        return (rc.getLocation().x - other.getLocation().x) * (rc.getLocation().x - other.getLocation().x)
                + (rc.getLocation().y - other.getLocation().y) * (rc.getLocation().y - other.getLocation().y);
    }

    int getDistanceFrom(MapLocation other) {
        return (rc.getLocation().x - other.x) * (rc.getLocation().x - other.x)
                + (rc.getLocation().y - other.y) * (rc.getLocation().y - other.y);
    }

    boolean isAdjacentTo(RobotInfo other) {
        return getDistanceFrom(other) == 1;
    }

    boolean isAdjacentTo(MapLocation loc) {
        return getDistanceFrom(loc) <= 2;
    }

    MapLocation adjacentTile(Direction dir) {
        return rc.getLocation().add(dir);
    }

    List<MapLocation> tilesInRange() {
        List<MapLocation> tiles = new LinkedList<>();
        int rsq = rc.getCurrentSensorRadiusSquared();
        int r = 1;
        while (r * r < rsq) r++;
        int x = rc.getLocation().x;
        int y = rc.getLocation().y;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                MapLocation pos = new MapLocation(x + dx, y + dy);
                // apparently doesn't check if on map
                if (x + dx < 0 || x + dx >= rc.getMapWidth() || y + dy < 0 || y + dy >= rc.getMapHeight()) continue;
                if (rc.canSenseLocation(pos)) { // within range and on map
                    tiles.add(pos);
                }
            }
        }
        return tiles;
    }


    public void getSpawnBase() {

        /*
            Information for units regarding production location, source (building) and direction
            assumes only one adjacent production source
        */

        RobotType spawnType;
        switch (rc.getType()) {
            case MINER:
                spawnType = RobotType.HQ;
                break;
            case DELIVERY_DRONE:
                spawnType = RobotType.FULFILLMENT_CENTER;
                break;
            case LANDSCAPER:
                spawnType = RobotType.DESIGN_SCHOOL;
                break;
            default:
                return; // ignore if robot is a building
        }
        MapLocation loc = rc.getLocation();
        for (Direction dir : directions) {
            RobotInfo robotAt = null;
            try {
                robotAt = rc.senseRobotAtLocation(loc.add(dir));
            } catch (GameActionException e) {
                e.printStackTrace();
            }
            if (robotAt != null && robotAt.getTeam().equals(rc.getTeam()) && robotAt.getType().equals(spawnType)) {
                System.out.println("found spawn base");
                spawnBase = loc.add(dir);
                spawnBaseDirTo = dir;
                spawnBaseDirFrom = dir.opposite();
                favourableDirection = spawnBaseDirFrom;
            }
        }
    }

    public void hqInfo() throws GameActionException {
        scanRobots();
        tryFindHomeHQLoc();
        tryFindEnemyHQLoc();
        getHQInfo();
    }

    public void scanRobots() throws GameActionException {
//        enemies = rc.senseNearbyRobots();
//        System.out.println("scan radius is "+rc.getCurrentSensorRadiusSquared()+", pollution is "+rc.sensePollution(rc.getLocation()));
        enemies = rc.senseNearbyRobots(-1, ENEMY);
        allies = rc.senseNearbyRobots(-1, ALLY);
    }

    public void tryFindHomeHQLoc() {
        if (allyHQ != null) return;
//        System.out.println("trying to find ally HQ out of " + allies.length + " options");
        for (RobotInfo ally : allies) {
            System.out.println("found home HQ location");
            if (ally.getType() == RobotType.HQ && ally.getTeam() == ALLY) {
                allyHQ = ally.getLocation();
                return;
            }
        }
    }

    public void tryFindEnemyHQLoc() throws GameActionException {
        if (enemyHQ != null) return;
        System.out.println("trying to find enemy HQ out of " + enemies.length + " options"
        +", with sensor range "+rc.getCurrentSensorRadiusSquared());
        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == RobotType.HQ && enemy.getTeam() == ENEMY) {
                System.out.println("found enemy HQ location");
                enemyHQ = enemy.getLocation();
                communicationHandler.sendEnemyHQLoc(enemyHQ);
                return;
            }
        }
    }

    public void getHQInfo() {
        if (rc.getRoundNum() == 1) return;
        if (allyHQ == null) {
            try {
                allyHQ = communicationHandler.receiveAllyHQLoc();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (enemyHQ == null) {
            try {
                enemyHQ = communicationHandler.receiveEnemyHQLoc();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public boolean onTheMap(MapLocation pos) {
        return (0 <= pos.x && pos.x < rc.getMapWidth() && 0 <= pos.y && pos.y < rc.getMapHeight());
    }

    public void searchSurroundings() throws GameActionException {};

    public MapLocation getNearestWaterTile() throws GameActionException {
        boolean[][] visited = new boolean[rc.getMapHeight()][rc.getMapWidth()];
        queue.clear();

        queue.add(rc.getLocation());
        visited[rc.getLocation().y][rc.getLocation().x] = true;

        while (!queue.isEmpty()) {
            MapLocation node = queue.poll();

            for (Direction dir : Direction.allDirections()) {
                MapLocation nnode = node.add(dir);
                if (!onTheMap(nnode)) continue;
                if (visited[nnode.y][nnode.x]) continue;
                while (containsWater[nnode.y][nnode.x] == null) {
                    if (!rc.isReady()) Clock.yield();
                    if (tryMove(movementSolver.directionToGoal(nnode))) {
                        // Update surroundings
                        // Done here (instead of using the updateSurroundings fuction as this way we can
                        // respond to changes in the map) (code speed > code quality I guess)

                        for (int dx = -4; dx <= 4; ++dx) {
                            for (int dy = -4; dy <= 4; ++dy) {
                                MapLocation sensePos = new MapLocation(
                                        rc.getLocation().x + dx,
                                        rc.getLocation().y + dy);

                                if (!rc.canSenseLocation(sensePos)) continue;

                                containsWater[sensePos.y][sensePos.x] = rc.senseFlooding(sensePos);

                                // If we have already visited this tile it must have a shorter distance then
                                // what we are looking at now
                                if (containsWater[sensePos.y][sensePos.x] && visited[sensePos.y][sensePos.x]) {
                                    return sensePos;
                                }
                            }
                        }

                    }
                }
                if (containsWater[nnode.y][nnode.x]) return nnode;
                queue.add(nnode);
                visited[nnode.y][nnode.x] = true;
            }

        }
        return null;

        /*
        // Find nearest water tile, without using any datastructures
        // (like a queue or visited set in normal BFS) (bad when it comes to exploring)
        int cx = rc.getLocation().x;
        int cy = rc.getLocation().y;
        if (containsWater[cy][cx]) return rc.getLocation();

        for (int offset = 1; ; ++offset) {
            // Trace out a square around current position
            for (int i = 0; i < offset + 1; ++i) {
                MapLocation c1 = new MapLocation(cx - offset + i, cy + offset);
                MapLocation c2 = new MapLocation(cx + offset, cy - offset + i);
                MapLocation c3 = new MapLocation(cx + offset - i, cy - offset);
                MapLocation c4 = new MapLocation(cx - offset, cy + offset - i);
                for (MapLocation pos : new MapLocation[] {c1, c2, c3, c4}) {
                    System.out.println("in here");
                    while (shouldExplore.test(pos)) {
                        rc.setIndicatorLine(rc.getLocation(), pos, 0,0, 255);
                            tryMove(movementSolver.directionToGoal(pos));

                    }
                    if (shouldReturn.test(pos)) return pos;
                }
            }
        }

         */
    }

    public MapLocation getNearestWaterTile2() throws GameActionException {
        for (int dx = -4; dx <= 4; ++dx) {
            for (int dy = -4; dy <= 4; ++dy) {
                MapLocation sensePos = new MapLocation(
                        rc.getLocation().x + dx,
                        rc.getLocation().y + dy);

                if (!rc.canSenseLocation(sensePos)) continue;

                containsWater[sensePos.y][sensePos.x] = rc.senseFlooding(sensePos);

                // If we have already visited this tile it must have a shorter distance then
                // what we are looking at now
                if (containsWater[sensePos.y][sensePos.x]) {
                    System.out.println("found water");
                    return sensePos;
                }
            }
        }
        return null;

        /*
        // Find nearest water tile, without using any datastructures
        // (like a queue or visited set in normal BFS) (bad when it comes to exploring)
        int cx = rc.getLocation().x;
        int cy = rc.getLocation().y;
        if (containsWater[cy][cx]) return rc.getLocation();

        for (int offset = 1; ; ++offset) {
            // Trace out a square around current position
            for (int i = 0; i < offset + 1; ++i) {
                MapLocation c1 = new MapLocation(cx - offset + i, cy + offset);
                MapLocation c2 = new MapLocation(cx + offset, cy - offset + i);
                MapLocation c3 = new MapLocation(cx + offset - i, cy - offset);
                MapLocation c4 = new MapLocation(cx - offset, cy + offset - i);
                for (MapLocation pos : new MapLocation[] {c1, c2, c3, c4}) {
                    System.out.println("in here");
                    while (shouldExplore.test(pos)) {
                        rc.setIndicatorLine(rc.getLocation(), pos, 0,0, 255);
                            tryMove(movementSolver.directionToGoal(pos));

                    }
                    if (shouldReturn.test(pos)) return pos;
                }
            }
        }

         */
    }

    public MapLocation getNearestBuildTile() throws GameActionException {
        boolean[][] visited = new boolean[rc.getMapHeight()][rc.getMapWidth()];
        queue.clear();

        queue.add(rc.getLocation());
        visited[rc.getLocation().y][rc.getLocation().x] = true;

        while (!queue.isEmpty()) {
            System.out.println("Searching for build tile");
            MapLocation node = queue.poll();

            for (Direction dir : Direction.allDirections()) {
                MapLocation nnode = node.add(dir);
                if (!onTheMap(nnode)) continue;
                if (visited[nnode.y][nnode.x]) continue;
                while (containsWater[nnode.y][nnode.x] == null) {
                    if (!rc.isReady()) Clock.yield();
                    if (tryMove(movementSolver.directionToGoal(nnode))) {
                        // Update surroundings
                        // Done here (instead of using the updateSurroundings fuction as this way we can
                        // respond to changes in the map) (code speed > code quality I guess)

                        for (int dx = -4; dx <= 4; ++dx) {
                            for (int dy = -4; dy <= 4; ++dy) {
                                MapLocation sensePos = new MapLocation(
                                        rc.getLocation().x + dx,
                                        rc.getLocation().y + dy);

                                if (!rc.canSenseLocation(sensePos)) continue;

                                containsWater[sensePos.y][sensePos.x] = rc.senseFlooding(sensePos);

                                // If we have already visited this tile it must have a shorter distance then
                                // what we are looking at now
                                if (!containsWater[sensePos.y][sensePos.x] &&
                                        (getDistanceSquared(sensePos, allyHQ) > 2) &&
                                        (rc.senseRobotAtLocation(sensePos) == null) &&
                                        visited[sensePos.y][sensePos.x]) {
                                    return sensePos;
                                }
                            }
                        }
                    }
                }
                if (containsWater[nnode.y][nnode.x]) continue;
                if (rc.senseRobotAtLocation(nnode) == null &&
                    getDistanceSquared(nnode, allyHQ) > 2) return nnode;
                queue.add(nnode);
                visited[nnode.y][nnode.x] = true;
            }

        }
        return null;
    }

    public MapLocation getNearestSoupTile() throws GameActionException {
        boolean[][] visited = new boolean[rc.getMapHeight()][rc.getMapWidth()];
        searchSurroundings();
        queue.clear();

        queue.add(rc.getLocation());
        visited[rc.getLocation().y][rc.getLocation().x] = true;

        while (!queue.isEmpty()) {
            MapLocation node = queue.poll();

            if (soupCount[node.y][node.x] == null ||
                    soupCount[node.y][node.x] > 0) return node;

            for (Direction dir : Direction.allDirections()) {
                MapLocation nnode = node.add(dir);
                if (!onTheMap(nnode)) continue;
                if (visited[nnode.y][nnode.x]) continue;
                queue.add(nnode);
                visited[nnode.y][nnode.x] = true;
            }

        }
        return null;
    }

    public void searchSurroundingsContinued() throws GameActionException {}


    void updateSeenBlocks() throws GameActionException {
        for (int i = lrsb; i < rc.getRoundNum(); ++i) {
            for (Transaction tx : rc.getBlock(i)) {
                int[] mess = tx.getMessage();
                if (communicationHandler.identify(mess) == CommunicationHandler.CommunicationType.MAPBLOCKS) {
                    MapLocation[] blocks = communicationHandler.getMapBlocks(mess);
                    for (MapLocation pos : blocks) {
                        seenBlocks[pos.y][pos.x] = true;
                    }
                }
            }
        }
        lrsb = rc.getRoundNum();
    }

    void updateMapBlocks() throws GameActionException {
        // For other version of miner
        for (int i = lrmb; i < rc.getRoundNum(); ++i) {
            for (Transaction tx : rc.getBlock(i)) {
                int[] mess = tx.getMessage();
                if (communicationHandler.identify(mess) == CommunicationHandler.CommunicationType.MAPBLOCK) {
                    MapBlock mb = communicationHandler.getMapBlock(mess);
                    mapBlocks[mb.pos.y][mb.pos.x] = mb;

                    if (visited != null) {
                        int BS = PlayerConstants.GRID_BLOCK_SIZE;
                        for (int x = BS * mb.pos.x; x < Math.min(BS * (mb.pos.x + 1), rc.getMapWidth()); ++x) {
                            for (int y = BS * mb.pos.y; y < Math.min(BS * (mb.pos.y + 1), rc.getMapHeight()); ++y) {
                                visited[y][x] = true;
                            }
                        }
                    }
                }
            }
        }
        lrmb = rc.getRoundNum(); // Keep track of last round we scanned the block chain
    }


    public void solveGhostHq() throws GameActionException {
        if (enemyHQ == null) {
            if (allyHQ != null) {
                int x = allyHQ.x;
                int y = allyHQ.y;

                MapLocation loc;

                if (ghostH) { // Horizontal symmetry
                    loc = new MapLocation(rc.getMapWidth()-x-1, y);
                    if (rc.canSenseLocation(loc)) {
                        if (rc.senseRobotAtLocation(loc) != null) {
                            if (rc.senseRobotAtLocation(loc).getType() != RobotType.HQ) {
                                ghostH = false;
                                communicationHandler.sendFailHorizontal();
                                System.out.println("no ghosts here");
                            } else {
                                if (enemyHQ == null) {
                                    enemyHQ = loc;
                                    communicationHandler.sendEnemyHQLoc(loc);
                                }
                            }
                        } else {
                            ghostV = false;
                            communicationHandler.sendFailVertical();
                            System.out.println("no ghosts here");
                        }
                    }
                }

                if (ghostV) { // Vertical symmetry
                    loc = new MapLocation(x, rc.getMapHeight()-y-1);
                    if (rc.canSenseLocation(loc)) {
                        if (rc.senseRobotAtLocation(loc) != null) {
                            if (rc.senseRobotAtLocation(loc).getType() != RobotType.HQ) {
                                ghostV = false;
                                communicationHandler.sendFailVertical();
                                System.out.println("no ghosts here");
                            } else {
                                if (enemyHQ == null) {
                                    enemyHQ = loc;
                                    communicationHandler.sendEnemyHQLoc(loc);
                                }
                            }
                        } else {
                            ghostV = false;
                            communicationHandler.sendFailVertical();
                            System.out.println("no ghosts here");
                        }
                    }
                }

                if (ghostR) { // Rotational symmetry
                    loc = new MapLocation(rc.getMapWidth()-x-1, rc.getMapHeight()-y-1);
                    if (rc.canSenseLocation(loc)) {
                        if (rc.senseRobotAtLocation(loc) != null) {
                            if (rc.senseRobotAtLocation(loc).getType() != RobotType.HQ) {
                                ghostR = false;
                                communicationHandler.sendFailRotational();
                                System.out.println("no ghosts here");
                            } else {
                                if (enemyHQ == null) {
                                    enemyHQ = loc;
                                    communicationHandler.sendEnemyHQLoc(loc);
                                }
                            }
                        } else {
                            ghostV = false;
                            communicationHandler.sendFailVertical();
                            System.out.println("no ghosts here");
                        }
                    }
                }
            }
        }
    }
    
    abstract public void run() throws GameActionException;
}
