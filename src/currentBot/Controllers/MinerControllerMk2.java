package currentBot.Controllers;

import battlecode.common.*;
import currentBot.*;

import java.util.LinkedList;
import java.util.Stack;

public class MinerControllerMk2 extends Controller {
    /*
        Current Miner strategy
        - Have two bots that perform BFS in one half of the map
        - The bots broadcast soup cluster locations
        - Other bots get assigned a soup location to mine from
        - After mining they go to other soup locations

     */
    public enum State {
        SEARCHURGENT,  // Goes to area where it knows soup already is
        MINE,          // Mines soup in range
        DEPOSIT,
        BUILD,
        EXPLORE  // Uses DFS to find soup locations (DFS > BFS)
    }

    State currentState = State.SEARCHURGENT;

    SimpleRandom random = new SimpleRandom();

    final int BLOCK_SIZE = PlayerConstants.GRID_BLOCK_SIZE;

    RobotType buildType = null;
    MapLocation buildLoc;


    boolean[][] visited = null;
    int[][] lastChecked = null;


    MapBlock currentSoupBlock; // Map block the miner goes to
    MapLocation currentSoupSquare; // Soup square the miner mines

    int xBlockSize;
    int yBlockSize;

    int born;

    Symmetry searchSymmetry = null; // Symmetry that explore bot sticks too

    public MinerControllerMk2(RobotController rc) {
        this.rc = rc;
        int round = rc.getRoundNum();
        this.born = round;

        xBlockSize = rc.getMapWidth() / BLOCK_SIZE + ((rc.getMapWidth() % BLOCK_SIZE == 0) ? 0 : 1);
        yBlockSize = rc.getMapHeight() / BLOCK_SIZE + ((rc.getMapHeight() % BLOCK_SIZE == 0) ? 0 : 1);

        mapBlocks = new MapBlock[yBlockSize][xBlockSize];

        this.movementSolver = new MovementSolver(this.rc, this);
        this.communicationHandler = new CommunicationHandler(this.rc, this);

        queue = new RingQueue<>(rc.getMapHeight() * rc.getMapWidth());

        // Check if a fulfillment center hasn't been built already
        boolean foundHQ = false;
        boolean builtFC = false;
        for (RobotInfo robotInfo : rc.senseNearbyRobots()) {
            if (robotInfo.getType() == RobotType.HQ) {foundHQ = true; allyHQ = robotInfo.location;}
            if (robotInfo.getType() == RobotType.FULFILLMENT_CENTER) {builtFC = true;}
        }


        if (!builtFC && foundHQ &&
                rc.getTeamSoup() > PlayerConstants.buildSoupRequirements(RobotType.FULFILLMENT_CENTER)) {
            currentState = State.BUILD;
            buildType = RobotType.FULFILLMENT_CENTER;
        }

        soupCount     = new Integer[rc.getMapHeight()][rc.getMapWidth()];
        containsWater = new Boolean[rc.getMapHeight()][rc.getMapWidth()];
        elevationMap  = new Integer[rc.getMapHeight()][rc.getMapWidth()];
        visited       = new boolean[rc.getMapHeight()][rc.getMapWidth()];
        lastChecked   = new int[rc.getMapHeight()][rc.getMapWidth()];

        enemyBuildMap = new RobotType[rc.getMapHeight()][rc.getMapWidth()];
        allyBuildMap  = new RobotType[rc.getMapHeight()][rc.getMapWidth()];

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
    }


    public void run() throws GameActionException {
        updateMapBlocks();

        for (int x = 0; x < xBlockSize; ++x) {
            for (int y = 0; y < yBlockSize; ++y) {
                if (mapBlocks[y][x] == null) continue;
                MapLocation pos = mapBlocks[y][x].pos;
                int x1 = pos.x * BLOCK_SIZE;
                int y1 = pos.y * BLOCK_SIZE;
                rc.setIndicatorLine(new MapLocation(x1, y1), new MapLocation(x1, y1 + BLOCK_SIZE - 1), 255, 0, 0);
                rc.setIndicatorLine(new MapLocation(x1, y1), new MapLocation(x1 + BLOCK_SIZE - 1, y1), 255, 0, 0);
                rc.setIndicatorLine(new MapLocation(x1 + BLOCK_SIZE - 1, y1 + BLOCK_SIZE - 1), new MapLocation(x1, y1 + BLOCK_SIZE - 1), 255, 0, 0);
                rc.setIndicatorLine(new MapLocation(x1 + BLOCK_SIZE - 1, y1 + BLOCK_SIZE - 1), new MapLocation(x1 + BLOCK_SIZE - 1, y1), 255, 0, 0);

            }
        }
        switch (currentState) {
            case MINE: execMine();                 break;
            case DEPOSIT: execDeposit();           break;
            case SEARCHURGENT: execSearchUrgent(); break;
        //    case BUILDER: execBuilder();           break;
            case EXPLORE: execExplore();           break;
        }
    }



    public void execMine() throws GameActionException {
        /*
            Assumes a miner is within a soup cluster
            Looks for soup then mines it until inventory is full

            If soup cluster is finished, tell everyone else and go to another one
         */

        updateMapBlocks();
        searchSurroundings();

        // If you can no longer carry any soup deposit it
        if (rc.getSoupCarrying() + GameConstants.SOUP_MINING_RATE > RobotType.MINER.soupLimit) {
            currentState = State.DEPOSIT;
            return;
        }

        if (currentSoupSquare == null) {
            currentSoupSquare = getNearestSoupTile();
        }

        movementSolver.restart();
        while (getDistanceSquared(rc.getLocation(), currentSoupSquare) > 1) {
            if (tryMove(movementSolver.directionToGoal(currentSoupSquare))) Clock.yield();
        }

        if (rc.senseSoup(currentSoupSquare) > 0) {
            if (!rc.isReady()) Clock.yield();

            while (rc.canMineSoup(rc.getLocation().directionTo(currentSoupSquare))) {
                rc.mineSoup(rc.getLocation().directionTo(currentSoupSquare));
                // Might as well do computation now
                searchSurroundings();
            }
        } else {
            soupCount[currentSoupSquare.y][currentSoupSquare.x] = 0;
            currentSoupSquare = null;
        }
    }

    public void execDeposit() throws GameActionException {
        searchSurroundings();

        // TODO: handle refineries
        MapLocation refineryPos = allyHQ;

        if (getDistanceSquared(rc.getLocation(), refineryPos) <= 1) {
            if (rc.canDepositSoup(rc.getLocation().directionTo(refineryPos))) {
                rc.depositSoup(rc.getLocation().directionTo(refineryPos), rc.getSoupCarrying());
                currentState = State.SEARCHURGENT;
            }
        } else {
            tryMove(movementSolver.directionToGoal(refineryPos));
        }
    }

    public void execSearchUrgent() throws GameActionException {
        // Decide which cluster each miner goes to by using ID's
        // So each cluster has the number of miners proportional to its size

        if (currentSoupBlock == null) {
            currentSoupSquare = null;
            updateMapBlocks();

            LinkedList<MapBlock> blocks = new LinkedList<>();
            int totalSoupSquares = 0;

            for (int y = 0; y < yBlockSize; ++y) {
                for (int x = 0; x < xBlockSize; ++x) {
                    if (mapBlocks[y][x] != null && mapBlocks[y][x].isReachable) blocks.add(mapBlocks[y][x]);
                }
            }

            for (MapBlock mb : blocks) {
                totalSoupSquares += mb.soupCount;
            }

            if (totalSoupSquares == 0) return;

            int v = this.rc.getID() % totalSoupSquares;
            int behind = 0;
            for (MapBlock mb : blocks) {
                if (v < behind + mb.soupCount) {
                    currentSoupBlock = mb;
                    break;
                }
                behind += mb.soupCount;
            }
        }

        movementSolver.restart();

        MapLocation target = new MapLocation(
                clamp(rc.getLocation().x,
                        currentSoupBlock.pos.x * BLOCK_SIZE,
                        (currentSoupBlock.pos.x + 1) * BLOCK_SIZE - 1),
                clamp(rc.getLocation().y,
                        currentSoupBlock.pos.y * BLOCK_SIZE,
                        (currentSoupBlock.pos.y + 1) * BLOCK_SIZE - 1));

        while (!isAdjacentTo(target)) {
            tryMove(movementSolver.directionToGoal(target));
            Clock.yield();
        }

        currentState = State.MINE;
    }

    public void execBuilder() throws GameActionException {
//        System.out.println("Builder stuff");
        if (buildLoc == null) {
            buildLoc = getNearestBuildTile();
        }

        if (!isAdjacentTo(buildLoc)) {
            tryMove(movementSolver.directionToGoal(buildLoc));
        } else {
            if (rc.getTeamSoup() > PlayerConstants.buildSoupRequirements(this.buildType)) {
                if (tryBuild(this.buildType, rc.getLocation().directionTo(buildLoc))) {
                    currentState = State.SEARCHURGENT;
                }
            }
        }
    }

    public MapLocation getMapBlock(MapLocation pos) {
        return new MapLocation(pos.x / BLOCK_SIZE, pos.y / BLOCK_SIZE);
    }

    public void determineMapBlock(MapLocation pos) throws GameActionException {
        // Determines and broadcasts information about the map block
        // Assumes you've sensed every tile in the map block

        int soup = 0;
        int enemies = 0;
        for (int x = pos.x * BLOCK_SIZE; x < (pos.x+1) * BLOCK_SIZE && x < rc.getMapWidth(); ++x) {
            for (int y = pos.y * BLOCK_SIZE; y < (pos.y+1) * BLOCK_SIZE && y < rc.getMapHeight(); ++y) {
                soup += soupCount[y][x];
                enemies += (enemyBuildMap[y][x] == null) ? 0 : 1;
            }
        }

        MapBlock mb = new MapBlock(pos, soup, enemies, true);
        mapBlocks[pos.y][pos.x] = mb;
        communicationHandler.sendMapBlock(mb);
    }

    public void searchSurroundings() throws GameActionException {
        for (int dx = -6; dx <= 6; ++dx) {
            for (int dy = -6; dy <= 6; ++dy) {
                MapLocation sensePos = new MapLocation(rc.getLocation().x + dx, rc.getLocation().y + dy);
                if (!rc.canSenseLocation(sensePos)) continue;

                if (rc.getRoundNum() - lastChecked[sensePos.y][sensePos.x] <= 10) continue;
                lastChecked[sensePos.y][sensePos.x] = rc.getRoundNum();

                // Check robot
                RobotInfo robot = rc.senseRobotAtLocation(sensePos);
                if (robot != null && (robot.team == rc.getTeam())) {
                    allyBuildMap[sensePos.y][sensePos.x] = robot.type;
                }

                if (robot != null && (robot.team == rc.getTeam().opponent())) {
                    enemyBuildMap[sensePos.y][sensePos.x] = robot.type;
                    if (robot.type == RobotType.HQ) communicationHandler.sendEnemyHQLoc(robot.location);
                }

                // Check elevation
                if (!rc.canSenseLocation(sensePos)) continue;
                elevationMap[sensePos.y][sensePos.x] = rc.senseElevation(sensePos);

                // Check water
                if (!rc.canSenseLocation(sensePos)) continue;
                containsWater[sensePos.y][sensePos.x] = rc.senseFlooding(sensePos);

                // Check soup
                soupCount[sensePos.y][sensePos.x] = rc.senseSoup(sensePos);
            }
        }
    }

    public void searchSurroundingsFast() throws GameActionException {
        for (int dx = -6; dx <= 6; ++dx) {
            for (int dy = -6; dy <= 6; ++dy) {
                MapLocation sensePos = new MapLocation(rc.getLocation().x + dx, rc.getLocation().y + dy);
                if (!rc.canSenseLocation(sensePos)) continue;

                if (lastChecked[sensePos.y][sensePos.x] != 0) continue;

                // Check elevation
                if (!rc.canSenseLocation(sensePos)) continue;
                elevationMap[sensePos.y][sensePos.x] = rc.senseElevation(sensePos);

                // Check water
                if (!rc.canSenseLocation(sensePos)) continue;
                containsWater[sensePos.y][sensePos.x] = rc.senseFlooding(sensePos);

                // Check soup
                soupCount[sensePos.y][sensePos.x] = rc.senseSoup(sensePos);

                lastChecked[sensePos.y][sensePos.x] = rc.getRoundNum();
            }
        }
    }

    public void execExplore() throws GameActionException {
        // Perform BFS on the map
        // At each step, check to see if we have identified a map chunk.
        visited = new boolean[rc.getMapHeight()][rc.getMapWidth()];

        // A slight optimization
        // Figures out how many squares there are in each mapblock and
        // only updates generates the mapblock when we have searched this many tiles
        int[][] sizes = new int[yBlockSize][xBlockSize];
        for (int x = 0; x < xBlockSize; ++x) {
            for (int y = 0; y < yBlockSize; ++y) {
                int w = (x < xBlockSize  - 1) ? BLOCK_SIZE : rc.getMapWidth() - BLOCK_SIZE * (xBlockSize - 1);
                int h = (y < yBlockSize - 1) ? BLOCK_SIZE : rc.getMapHeight() - BLOCK_SIZE * (yBlockSize - 1);
                sizes[y][x] = w * h;
            }
        }
        Stack<MapLocation> stack = new Stack<>();
        //queue.clear();
        stack.push(rc.getLocation());
        visited[rc.getLocation().y][rc.getLocation().x] = true;

        searchSurroundings();

        while (!stack.isEmpty()) {
            MapLocation node = stack.pop();
            System.out.println("Searching: " + node);

            // Check to see if this is the last square in a map block
            MapLocation mapBlock = getMapBlock(node);
            --sizes[mapBlock.y][mapBlock.x];
            if (sizes[mapBlock.y][mapBlock.x] == 0) determineMapBlock(mapBlock);

            for (Direction dir : Direction.allDirections()) {
                MapLocation nnode = node.add(dir);
                if (!onTheMap(nnode) || visited[nnode.y][nnode.x]) continue;

                while (soupCount[nnode.y][nnode.x] == null) {
                    tryMove(movementSolver.directionToGoal(nnode));
                    searchSurroundings();
                    Clock.yield();
                }

                // Don't explore if this node goes out side of the region of symmetry
                if (searchSymmetry == Symmetry.VERTICAL) {
                    if (getDistanceSquared(nnode, allyHQ) > getDistanceSquared(nnode,
                            new MapLocation(rc.getMapWidth() - allyHQ.x - 1, allyHQ.y))) continue;
                } else if (searchSymmetry == Symmetry.HORIZONTAL) {
                    if (getDistanceSquared(nnode, allyHQ) > getDistanceSquared(nnode,
                            new MapLocation(allyHQ.x, rc.getMapHeight() - allyHQ.y - 1))) continue;
                }

                // Search if the node is reachable
                if (containsWater[nnode.y][nnode.x]) continue;
                if (true) {//Math.abs(elevationMap[nnode.y][nnode.x] - elevationMap[node.y][node.x]) <= 3) {
                    visited[nnode.y][nnode.x] = true;
                    stack.push(nnode);
                }
            }
        }
        currentState = State.SEARCHURGENT;
    }

    public int clamp(int a, int l, int h) {
        return Math.min(Math.max(a, l), h);
    }
}
