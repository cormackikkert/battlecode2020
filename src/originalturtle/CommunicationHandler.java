package originalturtle;

import battlecode.common.*;

public class CommunicationHandler {
    Team team;
    RobotController rc;
    int teamSecret;

    CommunicationHandler(RobotController rc) {
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

        if (rc.canSubmitTransaction(message, 1)) {
            rc.submitTransaction(message, 1);
            return true;
        }
        return false;
    }
}
