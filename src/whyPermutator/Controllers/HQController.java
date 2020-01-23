package whyPermutator.Controllers;

import battlecode.common.*;
import whyPermutator.CommunicationHandler;
import whyPermutator.SoupCluster;

import java.util.LinkedList;

public class HQController extends Controller {
    boolean locationSent = false;

    int totalMiners;
//    int totalSoup = 0;
    int lastRound = 1;
    LinkedList<SoupCluster> soupClusters = new LinkedList<>();

    int compressedWidth;
    int compressedHeight;
    int BLOCK_SIZE = PlayerConstants.GRID_BLOCK_SIZE;
    int cap = 100000;
    boolean haveWallAround = false;

    int landscapersOnWall = 0;
    int dronesOnShield = 0;

    int totalCrudeSoup = 0;
    int totalSoupArea = 0;

    public HQController(RobotController rc) {
        this.allyHQ = rc.getLocation();
        getInfo(rc);
        totalMiners = 0;

        compressedHeight = rc.getMapHeight() / PlayerConstants.GRID_BLOCK_SIZE + ((rc.getMapHeight() % PlayerConstants.GRID_BLOCK_SIZE == 0) ? 0 : 1);
        compressedWidth = rc.getMapWidth() / PlayerConstants.GRID_BLOCK_SIZE + ((rc.getMapWidth() % PlayerConstants.GRID_BLOCK_SIZE == 0) ? 0 : 1);
        seenBlocks = new boolean[compressedHeight][compressedWidth];
    }

    void sendLandscapersOnWall() throws GameActionException {
        int ls = 0;
        for (Direction dir: getDirections()) {
            MapLocation newPos = rc.getLocation().add(dir);
            if (!rc.onTheMap(newPos)) {
                ++ls;
                continue;
            }
            RobotInfo robot = rc.senseRobotAtLocation(newPos);
            if (robot != null && robot.getType() == RobotType.LANDSCAPER && robot.getTeam() == rc.getTeam()) ++ls;
        }

        if (ls != landscapersOnWall) {
            communicationHandler.sendLandscapersOnWall(ls);
        }
        landscapersOnWall = ls;
    }

//    int[] dx = new int[] {-2, -2, -2, -1, 0, 1, 2, 2, 2, 2, 2, 1, 0, -1, -2, -2};
//    int[] dy = new int[] {0, 1, 2, 2, 2, 2, 2, 1, 0, -1, -2, -2, -2, -2, -2, -1};
//
//    void sendDronesOnShield() throws GameActionException {
//        int ds = 0;
//        for (int i = 0; i < 16; ++i) {
//            MapLocation newPos = new MapLocation(rc.getLocation().x + dx[i], rc.getLocation().y + dy[i]);
//            if (!rc.onTheMap(newPos)) {
//                ++ds;
//                continue;
//            }
//            if (!rc.canSenseLocation(newPos)) continue;
//            RobotInfo robot = rc.senseRobotAtLocation(newPos);
//            if (robot != null && robot.getType() == RobotType.DELIVERY_DRONE && robot.getTeam() == rc.getTeam()) ++ds;
//        }
//
//        if (ds != dronesOnShield) {
//            communicationHandler.sendDronesOnShield(ds);
//        }
//        dronesOnShield = ds;
//        System.out.println(ds);
//    }

    void updateClusters() throws GameActionException {
        for (int i = lastRound; i < rc.getRoundNum(); ++i) {
            for (Transaction tx : rc.getBlock(i)) {
                int[] mess = tx.getMessage();
                if (communicationHandler.identify(mess) == CommunicationHandler.CommunicationType.CLUSTER) {
                    SoupCluster broadcastedSoupCluster = communicationHandler.getCluster(mess);
                    System.out.println(broadcastedSoupCluster.x1 + " " + broadcastedSoupCluster.y1 + " " +
                            broadcastedSoupCluster.x2 + broadcastedSoupCluster.y2);
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
                    }

                }
            }
        }
        totalSoupArea = 0;
        totalCrudeSoup = 0;
        for (SoupCluster soupCluster : soupClusters) {
            totalSoupArea += soupCluster.size;
            totalCrudeSoup += soupCluster.crudeSoup;
        }

        lastRound = rc.getRoundNum(); // Keep track of last round we scanned the block chain
    }

    public void run() throws GameActionException {
        sendLandscapersOnWall(); // note only sends when it changes dw about soup consumption
        //System.out.println(landscapersOnWall);
//        sendDronesOnShield();
        hqInfo();

//        if (!sudokuSent && campOutside >= PlayerConstants.WAIT_FRIENDS_BEFORE_SUDOKU) {
//            communicationHandler.sendSudoku();
//            sudokuSent = true;
//            System.out.println("attack time");
//        }

        communicationHandler.solveEnemyHQLocWithGhosts();

        /*
            Prioritise shooting over creating miners? yes
         */
        scanRobots();
        for (RobotInfo enemy : enemies) {
            if (rc.canShootUnit(enemy.getID())) {
                rc.shootUnit(enemy.getID());
                System.out.println("fatality");
            }
        }

        // if (rc.getRoundNum() == 100) cap = totalSoup;
        for (int y = 0; y < compressedHeight; ++y) {
            for (int x = 0; x < compressedWidth; ++x) {
                if (!seenBlocks[y][x]) continue;
                int x1 = x * BLOCK_SIZE; int y1 = y * BLOCK_SIZE;
//                rc.setIndicatorLine(new MapLocation(x1, y1), new MapLocation(x1, y1 + BLOCK_SIZE - 1), 255, 0, 0);
//                rc.setIndicatorLine(new MapLocation(x1, y1), new MapLocation(x1 + BLOCK_SIZE - 1, y1), 255, 0, 0);
//                rc.setIndicatorLine(new MapLocation(x1 + BLOCK_SIZE - 1, y1 + BLOCK_SIZE - 1), new MapLocation(x1, y1 + BLOCK_SIZE - 1), 255, 0, 0);
//                rc.setIndicatorLine(new MapLocation(x1 + BLOCK_SIZE - 1, y1 + BLOCK_SIZE - 1), new MapLocation(x1 + BLOCK_SIZE - 1, y1), 255, 0, 0);

            }
        }


        if (this.rc.getRoundNum() == 1) {
            int HSize = this.rc.getMapHeight();
            int WSize = this.rc.getMapWidth();

            MapLocation HQPos = this.rc.getLocation();

            rc.buildRobot(RobotType.MINER, (HQPos.x > WSize / 2) ? Direction.WEST : Direction.EAST);
            Clock.yield();
            rc.buildRobot(RobotType.MINER, (HQPos.y > HSize / 2) ? Direction.SOUTH : Direction.NORTH);
            totalMiners += 2;
        }

        if (!locationSent) {
            if (communicationHandler.sendAllyHQLoc(allyHQ)) locationSent = true;
        }

        updateClusters();

        if (!haveWallAround) {

            for (RobotInfo robotInfo : allies) {
                if (robotInfo.getType() == RobotType.DESIGN_SCHOOL) {
                    haveWallAround = true;
                    break;
                }
            }
        }

        if (rc.getRoundNum() > 1900 && !sudokuSent) {
            communicationHandler.sendSudoku();
            sudokuSent = true;
        }

        if ((totalMiners < PlayerConstants.INSTA_BUILD_MINERS ||
                totalMiners < Math.min(totalSoupArea / PlayerConstants.AREA_PER_MINER, totalCrudeSoup / PlayerConstants.SOUP_PER_MINER) &&
                        rc.getTeamSoup() > PlayerConstants.minerSoupRequirements(totalMiners, rc.getRoundNum())) &&
            rc.getRoundNum() < 450 - 10) {

            for (Direction dir : directions) {
                    if (tryBuild(RobotType.MINER, dir)) {
                    System.out.println("miner++");
                    totalMiners++;
                    break;
                }
            }
        }

        readBlocks();
    }

    int lastPos = 1;
    public void readBlocks() throws GameActionException {
        while (lastPos < rc.getRoundNum() && Clock.getBytecodesLeft() > 500) {
            for (Transaction t : rc.getBlock(lastPos)) {
                int[] message = t.getMessage();
                switch (communicationHandler.identify(message)) {
                    case CLUSTER: processCluster(message); break;
                    case MAPBLOCKS: processMapBlocks(message); break;
                    case TOO_MUCH_DIE: processTooMuchDie(message); break;
                    case PLUS_ONE_CAMP: campOutside++; break;
                }
            }
        }
        ++lastPos;
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
                    visited[y][x] = true;
                }
            }
        }
        soupClusters.removeIf(sc -> sc.size == 0);
    }

    public void processTooMuchDie(int[] message) {
        sudoku = false;
        sudokuSent = false;
        campMessageSent = false;
        campOutside = 0;
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

}
