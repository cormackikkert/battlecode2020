package currentBot.Controllers;

import battlecode.common.*;

/**
 * New strategy since 14/01/2020:
 * > get soup
 * > landscapers
 * > drone defence
 * > drone attack (muddle up enemy formations)
 */
public class DeliveryDroneControllerMk2 extends Controller {
    private static final int SENSOR_RADIUS = 24;
    private static final int CHASE_RADIUS = 24;
    private static final int DEFENSE_RADIUS = 35; // radius from hq to defend
    private static final int NET_GUN_RADIUS = 15;
    private static final int CAMPING_RADIUS = 25; // for camping just outside net gun range
    private static final int OUTSIDE_NET_GUN_RADIUS = SENSOR_RADIUS;
    private static final int SWITCH_TO_ATTACK = 300; // turn for switching to attack mode

    enum State {
        DEFEND,
        ATTACK,
    }

    State currentState = null;

    public DeliveryDroneControllerMk2(RobotController rc) {
        getInfo(rc);
    }


    public void run() throws GameActionException {
        if (!rc.isReady()) return;

        assignRole();
        hqInfo();

        if (!rc.isCurrentlyHoldingUnit()) {
            switch (currentState) {
                case ATTACK: execAttackPatrol();            break;
                case DEFEND: execDefendPatrol();            break;
            }
        } else {
            switch (currentState) {
                case ATTACK: execAttackKill();              break;
                case DEFEND: execDefendKill();              break;
            }
        }
    }

    public void assignRole() throws GameActionException {

        /*
            Role assignment depending on turn. Early game defend, late game attack.
         */

        if (rc.getRoundNum() >= SWITCH_TO_ATTACK) {
            switchToAttackMode();
        } else {
            switchToDefenceMode();
        }
    }

    public void execDefendPatrol() throws GameActionException {

        /*
            Stay in perimeter around home base and pick up nearby enemy units.
            Should not try to chase enemies to pick them up because of cooldowns
         */

        // trying to pick up enemies
        for (RobotInfo enemy : enemies) {
            if (enemy.type == RobotType.LANDSCAPER || enemy.type == RobotType.MINER) {
                if (tryPickUpUnit(enemy)) return;
            }
        }

        // camp around home FIXME : case when allyHQ = null or guarantee get hq
        if (rc.getLocation().isWithinDistanceSquared(allyHQ, DEFENSE_RADIUS)) {
            tryMove(movementSolver.directionFromPoint(allyHQ));
            System.out.println("move to defend");
        } else {
            System.out.println("stand still to defend");
        }
    }

    public void execAttackPatrol() throws GameActionException {

        /*
            Stay in perimeter around enemy base and pick up nearby enemy units.
            Should not try to chase enemies to pick them up because of cooldowns
         */

        // trying to pick up enemies
        RobotInfo[] enemies = rc.senseNearbyRobots(CHASE_RADIUS, ENEMY);
        for (RobotInfo enemy : enemies) {
            if (enemy.type == RobotType.LANDSCAPER || enemy.type == RobotType.MINER) {
                if (tryPickUpUnit(enemy)) return;
            }
        }

        // camp outside enemy hq
        if (enemyHQ != null) {
            if (rc.getLocation().isWithinDistanceSquared(enemyHQ, NET_GUN_RADIUS)) {
                System.out.println("moving away from enemy hq at "+enemyHQ);
                tryMove(enemyHQ.directionTo(rc.getLocation()));
            } else if (rc.getLocation().isWithinDistanceSquared(enemyHQ, CAMPING_RADIUS)) {
                System.out.println("camping outside enemy hq");
            } else {
                System.out.println("moving directly to enemy hq");
                tryMove(movementSolver.droneDirectionToGoal(rc.getLocation(), enemyHQ));
            }
        } else if (allyHQ != null) { // move away from own hq
            tryMove(movementSolver.directionFromPoint(allyHQ));
            System.out.println("moving away from home hq");
        } else {
            tryMove(randomDirection());
        }
    }

    public void execDefendKill() throws GameActionException {
        execKill();
    }

    public void execAttackKill() throws GameActionException {
        execKill();
    }

    public void switchToAttackMode() throws GameActionException {
        currentState = State.ATTACK;
    }

    public void switchToDefenceMode() throws GameActionException {
        currentState = State.DEFEND;
    }

    public void execKill() throws GameActionException {

        /*
            move randomly until find water and drop unit // TODO improve like remembering water locations
         */

        for (Direction dir : directions) {
            if (rc.canDropUnit(dir) && rc.canSenseLocation(adjacentTile(dir)) && rc.senseFlooding(adjacentTile(dir))) {
                rc.dropUnit(dir);
                return;
            }
        }
        tryMove(randomDirection());
    }

    public boolean tryPickUpUnit(RobotInfo enemy) throws GameActionException {
        if (rc.canPickUpUnit(enemy.getID())) {
            rc.pickUpUnit(enemy.getID());
            System.out.println("picked up a "+enemy.getType());
            return true;
        }
        return false;
    }
}
