package originalturtle;

import battlecode.common.*;

public class CommunicationHandler {
    final static int clusterCode = 6969420;
    final static int allyHQCode = 4206969;
    final static int enemyHQCode = 6942069;

    Team team;
    RobotController rc;
    int teamSecret;

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

        message[1] = teamSecret ^ cluster.pos.x;
        message[2] = teamSecret ^ cluster.pos.y;
        message[3] = teamSecret ^ cluster.size;

        message[6] = teamSecret ^ clusterCode; // message type for cluster

        if (rc.canSubmitTransaction(message, 1)) {
            rc.submitTransaction(message, 1);
            return true;
        }
        return false;
    }

    public boolean sendAllyHQLoc(MapLocation loc) throws GameActionException {
        int[] message = new int[7];
        message[0] = teamSecret ^ rc.getRoundNum();

        message[1] = teamSecret ^ loc.x;
        message[2] = teamSecret ^ loc.y;

        message[6] = teamSecret ^ allyHQCode; // message type for allyhq

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

        message[1] = teamSecret ^ loc.x;
        message[2] = teamSecret ^ loc.y;

        message[6] = teamSecret ^ enemyHQCode; // message type for allyhq

        if (rc.canSubmitTransaction(message, 2)) {
            rc.submitTransaction(message, 2);
            return true;
        }
        return false;
    }

    public MapLocation receiveAllyHQLoc() throws GameActionException { // FIXME : is within restrictions?
        MapLocation out = null;
        for (int i = 1; i < rc.getRoundNum(); i++) {
            Transaction[] ally = rc.getBlock(i);
            for (Transaction t : ally) {
                int[] message = t.getMessage();
                if ((message[6] ^ allyHQCode) == teamSecret) {
                    out = new MapLocation(message[1] ^ teamSecret, message[2] ^ teamSecret);
                }
            }
        }
        System.out.println(out != null ? "Got HQ loc!" : "where home?");
        return out;
    }
}
