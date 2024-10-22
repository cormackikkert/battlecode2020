package whyPermutatorOld.Controllers;

import battlecode.common.*;
import whyPermutatorOld.CommunicationHandler;
import whyPermutatorOld.SoupCluster;

import java.util.LinkedList;

public class HQController extends Controller {
    boolean locationSent = false;

    int totalMiners;
    int totalSoup = 0;
    int lastRound = 1;
    LinkedList<SoupCluster> soupClusters = new LinkedList<>();

    int compressedWidth;
    int compressedHeight;
    int BLOCK_SIZE = PlayerConstants.GRID_BLOCK_SIZE;
    int cap = 100000;
    boolean haveWallAround = false;

    int landscapersOnWall = 0;

    public HQController(RobotController rc) {
        this.allyHQ = rc.getLocation();
        getInfo(rc);
        totalMiners = 0;

        compressedHeight = rc.getMapHeight() / PlayerConstants.GRID_BLOCK_SIZE + ((rc.getMapHeight() % PlayerConstants.GRID_BLOCK_SIZE == 0) ? 0 : 1);
        compressedWidth = rc.getMapWidth() / PlayerConstants.GRID_BLOCK_SIZE + ((rc.getMapWidth() % PlayerConstants.GRID_BLOCK_SIZE == 0) ? 0 : 1);
        seenBlocks = new boolean[compressedHeight][compressedWidth];
    }

    void sendLandscapersOnWall() throws GameActionException {
        int landscapers = 0;
        for (RobotInfo robot : rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rc.getTeam())) {
            if (robot.type == RobotType.LANDSCAPER && getChebyshevDistance(robot.getLocation(), rc.getLocation()) == 1) {
                landscapers++;
            }
        }
        if (landscapers != landscapersOnWall) {
            communicationHandler.sendLandscapersOnWall(landscapers);
        }
        landscapersOnWall = landscapers;
    }

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
        totalSoup = 0;
        for (SoupCluster soupCluster : soupClusters) totalSoup += soupCluster.crudeSoup;

        lastRound = rc.getRoundNum(); // Keep track of last round we scanned the block chain
    }

    public void run() throws GameActionException {
        communicationHandler.receiveTooMuchDie();
        communicationHandler.receivePLUSONE();
        sendLandscapersOnWall(); // note only sends when it changes dw about soup consumption

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

        updateSeenBlocks();
        updateClusters();

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

        if (enemyHQ == null) {
            communicationHandler.receiveEnemyHQLoc();
        }

        updateClusters();
        System.out.println(totalSoup);

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
                totalMiners < totalSoup / PlayerConstants.SOUP_PER_MINER) &&
                        rc.getTeamSoup() > PlayerConstants.minerSoupRequirements(totalMiners, rc.getRoundNum()) &&
            rc.getRoundNum() < 450 - 10) {

            for (Direction dir : directions) {
                    if (tryBuild(RobotType.MINER, dir)) {
                    System.out.println("miner++");
                    totalMiners++;
                    break;
                }
            }
        }

    }
}
