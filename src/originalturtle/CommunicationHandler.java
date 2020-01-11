package originalturtle;

import battlecode.common.*;

public class CommunicationHandler {
    Team team;
    RobotController rc;
    int teamSecret;

    public enum CommunicationType {
        ENEMY,
        CLUSTER
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
        message[2] = teamSecret ^ cluster.pos.x;
        message[3] = teamSecret ^ cluster.pos.y;
        message[4] = teamSecret ^ cluster.size;

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
                new MapLocation(message[2] ^ teamSecret, message[3] ^ teamSecret),
                message[4] ^ teamSecret);
    }
}
