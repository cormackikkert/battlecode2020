package originalturtle;

import battlecode.common.*;

public class CommunicationHandler {
    Team team;
    RobotController rc;
    int teamSecret;

    public enum CommunicationType {
        ENEMY,
        CLUSTER,
        ALLYHQ,
        ENEMYHQ
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
        message[1] = CommunicationType.CLUSTER.ordinal();
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
        message[1] = CommunicationType.CLUSTER.ordinal();
        message[2] = teamSecret ^ loc.x;
        message[3] = teamSecret ^ loc.y;

        if (rc.canSubmitTransaction(message, 2)) {
            rc.submitTransaction(message, 2);
            System.out.println("enemy location sent ");
            return true;
        }
        return false;
    }

    public MapLocation receiveAllyHQLoc() throws GameActionException { // FIXME : is within restrictions?
        MapLocation out = null;
        outer : for (int i = 1; i < rc.getRoundNum(); i++) {
            Transaction[] ally = rc.getBlock(i);
            for (Transaction t : ally) {
                int[] message = t.getMessage();
                if ((message[2] ^ teamSecret) == CommunicationType.ALLYHQ.ordinal()) {
                    out = new MapLocation(message[2] ^ teamSecret, message[3] ^ teamSecret);
                    break outer;
                }
            }
        }
//        System.out.println(out != null ? "Got HQ loc!" : "where home?");
        return out;
    }

    public MapLocation receiveEnemyHQLoc() throws GameActionException {
        MapLocation out = null;
        outer : for (int i = 1; i < rc.getRoundNum(); i++) {
            Transaction[] ally = rc.getBlock(i);
            for (Transaction t : ally) {
                int[] message = t.getMessage();
                if ((message[2] ^ teamSecret) == CommunicationType.ENEMYHQ.ordinal()) {
                    out = new MapLocation(message[2] ^ teamSecret, message[3] ^ teamSecret);
                    break outer;
                }
            }
        }
//        System.out.println(out != null ? "Got HQ loc!" : "where home?");
        return out;
    }
}
