package currentBot.Controllers;

import battlecode.common.*;
import currentBot.CommunicationHandler;
import currentBot.SoupCluster;

import java.util.LinkedList;

public class HQController extends Controller {
    boolean locationSent = false;

    int totalMiners;
    int totalSoup = 0;
    int lastRound = 1;
    LinkedList<SoupCluster> soupClusters = new LinkedList<>();

    public HQController(RobotController rc) {
        this.allyHQ = rc.getLocation();
        getInfo(rc);
        totalMiners = 0;
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
                        // broadcastedSoupCluster.draw(this.rc);
                        soupClusters.add(broadcastedSoupCluster);
                    }

                }
            }
        }
        totalSoup = 0;
        for (SoupCluster soupCluster : soupClusters) totalSoup += soupCluster.size;

        lastRound = rc.getRoundNum(); // Keep track of last round we scanned the block chain
    }

    public void run() throws GameActionException {
        /*
            Prioritise shooting over creating miners?
         */
        scanRobots();
        for (RobotInfo enemy : enemies) {
            if (rc.canShootUnit(enemy.getID())) {
                rc.shootUnit(enemy.getID());
            }
        }

        updateClusters();

        if (this.rc.getRoundNum() == 1) {
            int HSize = this.rc.getMapHeight();
            int WSize = this.rc.getMapWidth();

            MapLocation HQPos = this.rc.getLocation();

            rc.buildRobot(RobotType.MINER, (HQPos.x > WSize / 2) ? Direction.WEST : Direction.EAST);
            Clock.yield();
            rc.buildRobot(RobotType.MINER, (HQPos.y > HSize / 2) ? Direction.SOUTH : Direction.NORTH);
        }

        if (!locationSent) {
            if (communicationHandler.sendAllyHQLoc(allyHQ)) locationSent = true;
        }

        updateClusters();

        if (totalMiners < totalSoup / PlayerConstants.SOUP_PER_MINER + 4) {
            for (Direction dir : directions) {
                if (tryBuild(RobotType.MINER, dir)) {totalMiners++; break;}
            }
        }
    }
}
