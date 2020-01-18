package currentBot;

import battlecode.common.*;
import currentBot.Controllers.Controller;
import currentBot.Controllers.LandscaperController;

import static currentBot.CommunicationHandler.CommunicationType.*;

public class CommunicationHandler { // TODO : conserve bytecode by storing turn of last received message
    public static final int MESSAGE_COST = 1;
    Team team;
    RobotController rc;
    Controller controller;
    int teamSecret;

    public enum CommunicationType {
        ENEMY,
        CLUSTER,
        MAPBLOCKS, // Blocks the miners have searched
        ALLYHQ,
        ENEMYHQ,
        REQUEST_BUILD,
        SCOUTDIRECTION,
        FAILHORIZONTAL, // for detecting enemy hq
        FAILVERTICAL,
        FAILROTATIONAL,
        HITCHHIKE_REQUEST,
        HITCHHIKE_ACK,
        CLEAR_FLOOD,
        LANDSCAPE_DEFEND,
        LANDSCAPE_HELP
    }

    public CommunicationHandler(RobotController rc) {
        // TODO: make this not garbage (Though surely no-one actually tries to decode this)
        this.rc = rc;
        this.team = rc.getTeam();
        teamSecret = (this.team == Team.A) ? 1129504 : 1029304;
    }

    public CommunicationHandler(RobotController rc, Controller controller) {
        // TODO: make this not garbage (Though surely no-one actually tries to decode this)
        this.rc = rc;
        this.controller = controller;
        this.team = rc.getTeam();
        teamSecret = (this.team == Team.A) ? 1129504 : 1029304;
    }

    /*
        XORs stuff as it is reversible
     */

    private int[] bluePrint(CommunicationType message) {
        int[] arr = new int[7];
        arr[0] = (message.ordinal() << 25);
        return arr;
    }

    void encode(int[] arr) {
        for (int i = 0; i < 7; ++i) arr[i] ^= teamSecret;
    }

    void decode(int[] arr) {
        encode(arr);
    }

    public CommunicationType identify(int[] message) {
        if (message[0] % (1 << 25) == teamSecret && message.length == 7) {
            return CommunicationType.values()[message[0] >> 25];
        }
        return CommunicationType.ENEMY;
    }

    public boolean sendCluster(SoupCluster cluster) throws GameActionException {
        System.out.println("Sending soup cluster info: " + rc.getID());
        int[] message = bluePrint(CommunicationType.CLUSTER);

        for (int val : new int[] {cluster.x1, cluster.y1, cluster.x2, cluster.y2}) {
            message[1] <<= 8;
            message[1] += val;
        }

        message[2] = cluster.size;

        message[3] = cluster.crudeSoup;

        message[4] = cluster.containsWaterSoup ? 1 : 0;

        //System.out.println("BEFORE: " + message[2]);
        encode(message);
        //System.out.println("AFTER: " + message[2]);
        // TODO: flags for enemys

        if (rc.canSubmitTransaction(message, 1)) {
            rc.submitTransaction(message, 1);
            return true;
        }
        return false;
    }

    public SoupCluster getCluster(int[] message) {
        decode(message);
        int y2 = message[1] % (1 << 8); message[1] >>= 8;
        int x2 = message[1] % (1 << 8); message[1] >>= 8;
        int y1 = message[1] % (1 << 8); message[1] >>= 8;
        int x1 = message[1] % (1 << 8); message[1] >>= 8;

        return new SoupCluster(x1, y1, x2, y2, message[2], message[3], message[4] == 1);
    }

    public boolean sendMapBlocks(MapLocation[] blocks) throws GameActionException {
        int[] message = bluePrint(CommunicationType.MAPBLOCKS);
        int i = 0;
        for (int row = 1; row <= 6; ++row) {
            message[row] <<= 8;
            message[row] |= blocks[i].x;
            message[row] <<= 8;
            message[row] |= blocks[i].y;
            i = (i + 1) % blocks.length;
        }

        encode(message);

        if (rc.canSubmitTransaction(message, 1)) {
            rc.submitTransaction(message, 1);
            return true;
        }
        return false;
    }

    public boolean sendHitchHikeRequest(HitchHike req) throws GameActionException {
        int[] message = bluePrint(CommunicationType.HITCHHIKE_REQUEST);
        System.out.println("Sending: " + req.pos + " " + req.goal);
        message[1] = req.pos.x;
        message[2] = req.pos.y;
        message[3] = req.goal.x;
        message[4] = req.goal.y;

        encode(message);

        if (rc.canSubmitTransaction(message, 1)) {
            rc.submitTransaction(message, 1);
            return true;
        }
        return false;
    }

    public HitchHike getHitchHikeRequest(int[] message) throws GameActionException {
        decode(message);
        return new HitchHike(new MapLocation(message[1], message[2]),
                new MapLocation(message[3], message[4]));
    }
    public boolean sendHitchHikeAck(HitchHike req) throws GameActionException {
        int[] message = bluePrint(CommunicationType.HITCHHIKE_ACK);

        message[1] = req.pos.x;
        message[2] = req.pos.y;
        message[3] = req.goal.x;
        message[4] = req.goal.y;
        message[5] = req.droneID;

        encode(message);

        if (rc.canSubmitTransaction(message, 1)) {
            rc.submitTransaction(message, 1);
            return true;
        }
        return false;
    }

    public HitchHike getHitchHikeAck(int[] message) throws GameActionException {
        decode(message);
        return new HitchHike(new MapLocation(message[1], message[2]),
                new MapLocation(message[3], message[4]),
                message[5]);
    }

    public MapLocation[] getMapBlocks(int[] message) {
        decode(message);

        MapLocation[] blocks = new MapLocation[12];
        for (int row = 1; row < 7; ++row) {
            for (int i = 0; i < 2; ++i) {
                int y = message[row] % (1 << 8); message[row] >>= 8;
                int x = message[row] % (1 << 8); message[row] >>= 8;
                blocks[2*(row-1)+i] = new MapLocation(x, y);
            }
        }
        return blocks;
    }

    boolean sentAlly = false;
    public boolean sendAllyHQLoc(MapLocation loc) throws GameActionException {
        if (loc == null || sentAlly) return false;
        int[] message = bluePrint(CommunicationType.ALLYHQ);
//        message[0] = teamSecret ^ rc.getRoundNum();
//        message[1] = CommunicationType.ALLYHQ.ordinal();
        message[2] = loc.x;
        message[3] = loc.y;

        encode(message);
        if (rc.canSubmitTransaction(message, 2)) {
            rc.submitTransaction(message, 2);
            System.out.println("home location sent ");
            sentAlly = true;
            return true;
        }
        return false;
    }

    boolean sentEnemy = false;
    public boolean sendEnemyHQLoc(MapLocation loc) throws GameActionException {
        if (loc == null || sentEnemy) return false;
        int[] message = bluePrint(CommunicationType.ENEMYHQ);
//        message[0] = teamSecret ^ rc.getRoundNum();
//        message[1] = teamSecret ^ CommunicationType.ENEMYHQ.ordinal();
        message[2] = loc.x;
        message[3] = loc.y;

        encode(message);
        if (rc.canSubmitTransaction(message, 2)) {
            rc.submitTransaction(message, 2);
            System.out.println("enemy location sent "+loc);
            sentEnemy = true;
            return true;
        }
        return false;
    }

    int turnA = 1;
    public MapLocation receiveAllyHQLoc() throws GameActionException { // FIXME : reimplement this later
        // TODO: use my encode and decode methods lol
        // also fyi I put this sort of thing in my miner class so I avoid scanning
        // the same part of the block chain multiple times
        MapLocation out = null;
        outer : for (int i = turnA
                     ; i < rc.getRoundNum(); i++) {
            turnA++;
            Transaction[] ally = rc.getBlock(i);
            for (Transaction t : ally) {
                int[] message = t.getMessage();
                if (identify(message) == CommunicationType.ALLYHQ) {
                    decode(message);
                    out = new MapLocation(message[2], message[3]);
                    System.out.println("received ally location");
                    break outer;
                }
            }
        }
        return out;
    }

    int turnE = 1;
    public MapLocation receiveEnemyHQLoc() throws GameActionException {
        MapLocation out = null;
        outer : for (int i = turnE
                     ; i < rc.getRoundNum(); i++) {
            turnE++;
            Transaction[] ally = rc.getBlock(i);
            for (Transaction t : ally) {
                int[] message = t.getMessage();
                if (identify(message) == CommunicationType.ENEMYHQ) {
                    decode(message);
                    out = new MapLocation(message[2], message[3]);
                    System.out.println("received enemy location "+out);
                    break outer;
                }
            }
        }
        return out;
    }

    public boolean sendScoutDirection(MapLocation allyHQ, boolean horizontal) throws GameActionException {
        int HSize = this.rc.getMapHeight();
        int WSize = this.rc.getMapWidth();

        int x = allyHQ.x;
        int y = allyHQ.y;

        MapLocation loc;
        if (horizontal) {
            loc = new MapLocation(x, HSize - y);
        } else {
            loc = new MapLocation(WSize - x, y);
        }

        int[] message = new int[7];
        message[0] = teamSecret ^ rc.getRoundNum();
        message[1] = teamSecret ^ CommunicationType.SCOUTDIRECTION.ordinal();
        message[2] = teamSecret ^ loc.x;
        message[3] = teamSecret ^ loc.y;

        if (rc.canSubmitTransaction(message, MESSAGE_COST)) {
            rc.submitTransaction(message, MESSAGE_COST);
            System.out.println("scout direction sent ");
            return true;
        }
        System.out.println("cannot submit, have only "+rc.getTeamSoup()+" soup");
        return false;
    }

    public MapLocation receiveScoutLocation(int spawnTurn) throws GameActionException {
        int turn = spawnTurn - 1;
        Transaction[] ally = rc.getBlock(turn);
        for (Transaction t : ally) {
            int[] message = t.getMessage();
            System.out.println((CommunicationType.SCOUTDIRECTION.ordinal() ^ teamSecret));
            if ((CommunicationType.SCOUTDIRECTION.ordinal() ^ teamSecret) == message[1]) {
                System.out.println("got message on turn "+turn);
                return new MapLocation(message[2] ^ teamSecret, message[3] ^ teamSecret);
            }
        }
        System.out.println("got nothing on turn "+turn);
        return null;
    }

    public boolean sendFailVertical() throws GameActionException {
        int[] message = new int[7];
        message[0] = rc.getRoundNum();
        message[6] = CommunicationType.FAILVERTICAL.ordinal();
        encode(message);
        if (rc.canSubmitTransaction(message, MESSAGE_COST)) {
            rc.submitTransaction(message, MESSAGE_COST);
            System.out.println("send message for fail found vert");
            return true;
        }
        return false;
    }

    public boolean sendFailHorizontal() throws GameActionException {
        int[] message = new int[7];
        message[0] = rc.getRoundNum();
        message[6] = CommunicationType.FAILHORIZONTAL.ordinal();
        encode(message);
        if (rc.canSubmitTransaction(message, MESSAGE_COST)) {
            rc.submitTransaction(message, MESSAGE_COST);
            System.out.println("send message for fail found hori");
            return true;
        }
        return false;
    }

    public boolean sendFailRotational() throws GameActionException {
        int[] message = new int[7];
        message[0] = rc.getRoundNum();
        message[6] = CommunicationType.FAILROTATIONAL.ordinal();
        encode(message);
        if (rc.canSubmitTransaction(message, MESSAGE_COST)) {
            rc.submitTransaction(message, MESSAGE_COST);
            System.out.println("send message for fail found hori");
            return true;
        }
        return false;
    }

    int turnF = 1;
    public void solveEnemyHQLocWithGhosts() throws GameActionException { // ONLY HQ SHOULD BE DOING THIS
        if (controller.ghostsKilled == 2) return; //already know enemy hq

        for (int i = turnF; i < rc.getRoundNum(); i++) {
            turnF++;
            Transaction[] transactions = rc.getBlock(i);
            for (Transaction transaction : transactions) {
                int[] message= transaction.getMessage();
                decode(message);

                if (message[6] == CommunicationType.FAILHORIZONTAL.ordinal()) {
                    controller.ghostH = false;
                    controller.ghostsKilled++;
                    System.out.println("not horizontal symmetry");
                }

                if (message[6] == CommunicationType.FAILVERTICAL.ordinal()) {
                    controller.ghostV = false;
                    controller.ghostsKilled++;
                    System.out.println("not vertical symmetry");
                }

                if (message[6] == CommunicationType.FAILROTATIONAL.ordinal()) {
                    controller.ghostR = false;
                    controller.ghostsKilled++;
                    System.out.println("not rotational symmetry");
                }
            }
        }

        int x = controller.allyHQ.x;
        int y = controller.allyHQ.y;

        if (controller.ghostsKilled == 2) {
            MapLocation enemyHQ;
            if (controller.ghostH) {
                enemyHQ = new MapLocation(rc.getMapWidth()-x-1, y);
            } else if (controller.ghostV) {
                enemyHQ = new MapLocation(x, rc.getMapHeight()-y-1);
            } else { // rotational
                enemyHQ = new MapLocation(rc.getMapWidth()-x-1, rc.getMapHeight()-y-1);
            }

            System.out.println("found hq without even going near it");
            sendEnemyHQLoc(enemyHQ);
            controller.enemyHQ = enemyHQ;
        }
    }

    public void askClearSoupFlood(SoupCluster cluster) throws GameActionException {
        if (cluster == null) return;
        int[] message = bluePrint(CLEAR_FLOOD);
        message[1] = cluster.x1;
        message[2] = cluster.y1;
        message[3] = cluster.x2;
        message[4] = cluster.y2;
        message[6] = 69 ^ teamSecret;
        encode(message);
        if (rc.canSubmitTransaction(message, MESSAGE_COST)) {
            rc.submitTransaction(message, MESSAGE_COST);
        }
    }

    int turnFF = 1;
    public void receiveClearSoupFlood() throws GameActionException {
        MapLocation out = null;
        outer : for (int i = turnFF
                     ; i < rc.getRoundNum(); i++) {
            turnFF++;
            Transaction[] ally = rc.getBlock(i);
            for (Transaction t : ally) {
                int[] message = t.getMessage();
                if (identify(message) == CLEAR_FLOOD) {
                    decode(message);

                    SoupCluster soupCluster = new SoupCluster(message[1], message[2], message[3], message[4]);


                    ((LandscaperController) controller).currentSoupCluster = soupCluster;
                    ((LandscaperController) controller).currentState = LandscaperController.State.REMOVE_WATER;

                    System.out.println("unflood soup at "+soupCluster.middle.x+" "+soupCluster.middle.y);
                    break outer;
                }
            }
        }
    }

    public void landscapeDefend(int id) throws GameActionException {
        int[] message = bluePrint(LANDSCAPE_DEFEND);

        message[1] = id;
        encode(message);

        if (rc.canSubmitTransaction(message, MESSAGE_COST)) {
            rc.submitTransaction(message, MESSAGE_COST);
            System.out.println("created defend landscaper");
        }
    }

    public void landscapeHelp(int id) throws GameActionException {
        int[] message = bluePrint(LANDSCAPE_HELP);

        message[1] = id;
        encode(message);

        if (rc.canSubmitTransaction(message, MESSAGE_COST)) {
            rc.submitTransaction(message, MESSAGE_COST);
            System.out.println("created helper landscaper");
        }
    }


    int turnLD = 1;
    public void receiveLandscapeRole() throws GameActionException {
        outer : for (int i = turnLD
                     ; i < rc.getRoundNum(); i++) {
            turnLD++;
            for (Transaction t : rc.getBlock(i)) {
                int[] message = t.getMessage();

                if (identify(message) == LANDSCAPE_DEFEND) {
                    decode(message);
                    if (message[1] == controller.rc.getID()) {
                        ((LandscaperController) controller).currentState = LandscaperController.State.PROTECTHQ;
                        System.out.println("wall time");
                        break outer;
                    }
                }

                if (identify(message) == LANDSCAPE_HELP) {
                    decode(message);
                    if (message[1] == controller.rc.getID()) {
                        ((LandscaperController) controller).currentState = LandscaperController.State.REMOVE_WATER;
                        System.out.println("help remove water");
                        break outer;
                    }
                }

            }
        }
    }
}
