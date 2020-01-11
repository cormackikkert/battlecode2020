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
    public boolean sendCluster(SoupCluster cluster) throws GameActionException {
        int[] message = new int[7];
        message[0] = teamSecret ^ rc.getRoundNum(); // Stop opposition from resending same message 1 round later
        message[1] = CommunicationType.CLUSTER.ordinal();
        message[2] = teamSecret ^ cluster.x1;
        message[3] = teamSecret ^ cluster.y1;
        message[4] = teamSecret ^ cluster.x2;
        message[5] = teamSecret ^ cluster.y2;
        message[6] = teamSecret ^ cluster.size;

        // TODO: flags for enemys

        if (rc.canSubmitTransaction(message, 1)) {
            rc.submitTransaction(message, 1);
            return true;
        }
        return false;
    }

    public CommunicationType identify(int[] message, int round) {
        if ((message[0] ^ round) == teamSecret) {
            // System.out.println("HMM - " + (message[1]) + " " + CommunicationType.values().length);
            return CommunicationType.values()[message[1]];
        }
        return CommunicationType.ENEMY;
    }

    public SoupCluster getCluster(int[] message, int round) {
        return getCluster(message);
    }

    public SoupCluster getCluster(int[] message) {

        return new SoupCluster(
                message[2] ^ teamSecret, message[3] ^ teamSecret, message[4] ^ teamSecret,
                message[5] ^ teamSecret, message[6] ^ teamSecret);
    }

    public boolean sendAllyHQLoc(MapLocation loc) throws GameActionException {
        int[] message = new int[7];
        message[0] = teamSecret ^ rc.getRoundNum();
        message[1] = CommunicationType.CLUSTER.ordinal();
        message[2] = teamSecret ^ loc.x;
        message[3] = teamSecret ^ loc.y;

        if (rc.canSubmitTransaction(message, 2)) {
            rc.submitTransaction(message, 2);
            System.out.println("loc sent "+message[6]);
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
        System.out.println(out != null ? "Got HQ loc!" : "where home?");
        return out;
    }
}
