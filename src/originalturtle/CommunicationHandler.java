package originalturtle;

import battlecode.common.*;

public class CommunicationHandler { // TODO : conserve bytecode by storing turn of last received message
    Team team;
    RobotController rc;
    int teamSecret;
    int turn = 1;

    public enum CommunicationType {
        ENEMY,
        CLUSTER,
        ALLYHQ,
        ENEMYHQ,
        SCOUTDIRECTION,
        FAILHORIZONTAL, // for detecting enemy hq
        FAILVERTICAL
    }
    public CommunicationHandler(RobotController rc) {
        // TODO: make this not garbage (Though surely no-one actually tries to decode this)
        this.rc = rc;
        this.team = rc.getTeam();
        teamSecret = (this.team == Team.A) ? 1129504 : 29103849;
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

    public boolean sendCluster(SoupCluster cluster) throws GameActionException {
        int[] message = bluePrint(CommunicationType.CLUSTER);

        for (int val : new int[] {cluster.x1, cluster.y1, cluster.x2, cluster.y2}) {
            message[1] <<= 8;
            message[1] += val;
        }

        message[2] = cluster.size;

        for (int val : new int[] {cluster.refinery.x, cluster.refinery.y}) {
            message[3] <<= 8;
            message[3] += val;
        }
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

    public CommunicationType identify(int[] message, int round) {
        if (message[0] % (1 << 25) == teamSecret) {
            return CommunicationType.values()[message[0] >> 25];
        }
        return CommunicationType.ENEMY;
    }

    public SoupCluster getCluster(int[] message, int round) {
        return getCluster(message);
    }

    public SoupCluster getCluster(int[] message) {
        decode(message);
        int y2 = message[1] % (1 << 8); message[1] >>= 8;
        int x2 = message[1] % (1 << 8); message[1] >>= 8;
        int y1 = message[1] % (1 << 8); message[1] >>= 8;
        int x1 = message[1] % (1 << 8); message[1] >>= 8;

        int ry = message[3] % (1 << 8); message[3] >>= 8;
        int rx = message[3] % (1 << 8); message[3] >>= 8;

        return new SoupCluster(x1, y1, x2, y2, message[2], new MapLocation(rx, ry));
    }

    public boolean sendAllyHQLoc(MapLocation loc) throws GameActionException {
        int[] message = new int[7];
        message[0] = teamSecret ^ rc.getRoundNum();
        message[1] = teamSecret ^ CommunicationType.CLUSTER.ordinal();
        message[2] = teamSecret ^ loc.x;
        message[3] = teamSecret ^ loc.y;

        if (rc.canSubmitTransaction(message, 2)) {
            rc.submitTransaction(message, 2);
            System.out.println("home location sent ");
            return true;
        }
        return false;
    }

    public boolean sendEnemyHQLoc(MapLocation loc) throws GameActionException {
        if (loc == null) return false;
        int[] message = new int[7];
        message[0] = teamSecret ^ rc.getRoundNum();
        message[1] = teamSecret ^ CommunicationType.ENEMYHQ.ordinal();
        message[2] = teamSecret ^ loc.x;
        message[3] = teamSecret ^ loc.y;

        if (rc.canSubmitTransaction(message, 2)) {
            rc.submitTransaction(message, 2);
            System.out.println("enemy location sent ");
            return true;
        }
        return false;
    }

    public MapLocation receiveAllyHQLoc() throws GameActionException { // FIXME : reimplement this later
        MapLocation out = null;
        outer : for (int i = rc.getRoundNum()-1
                     ; i < rc.getRoundNum(); i++) {
            Transaction[] ally = rc.getBlock(i);
            for (Transaction t : ally) {
                int[] message = t.getMessage();
                if ((CommunicationType.ALLYHQ.ordinal() ^ teamSecret) == message[1]) {
                    out = new MapLocation(message[2] ^ teamSecret, message[3] ^ teamSecret);
                    break outer;
                }
            }
        }
        return out;
    }

    public MapLocation receiveEnemyHQLoc() throws GameActionException {
        MapLocation out = null;
        outer : for (int i = rc.getRoundNum()-1
                     ; i < rc.getRoundNum(); i++) {
            Transaction[] ally = rc.getBlock(i);
            for (Transaction t : ally) {
                int[] message = t.getMessage();
                if ((CommunicationType.ENEMYHQ.ordinal() ^ teamSecret) == message[1]) {
                    out = new MapLocation(message[2] ^ teamSecret, message[3] ^ teamSecret);
                    System.out.println("received enemy location");
                    break outer;
                }
            }
        }
        return out;
    }

    public static final int SCOUT_MESSAGE_COST = 5;
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

        if (rc.canSubmitTransaction(message, SCOUT_MESSAGE_COST)) {
            rc.submitTransaction(message, SCOUT_MESSAGE_COST);
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
        message[0] = teamSecret ^ rc.getRoundNum();
        message[1] = teamSecret ^ CommunicationType.FAILVERTICAL.ordinal();
        if (rc.canSubmitTransaction(message, SCOUT_MESSAGE_COST)) {
            rc.submitTransaction(message, SCOUT_MESSAGE_COST);
            System.out.println("send message for fail found vert");
            return true;
        }
        return false;
    }

    public boolean sendFailHorizontal() throws GameActionException {
        int[] message = new int[7];
        message[0] = teamSecret ^ rc.getRoundNum();
        message[1] = teamSecret ^ CommunicationType.FAILHORIZONTAL.ordinal();
        if (rc.canSubmitTransaction(message, SCOUT_MESSAGE_COST)) {
            rc.submitTransaction(message, SCOUT_MESSAGE_COST);
            System.out.println("send message for fail found hori");
            return true;
        }
        return false;
    }
}
