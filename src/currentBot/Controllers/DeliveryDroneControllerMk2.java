package currentBot.Controllers;

import battlecode.common.*;
import currentBot.RingQueue;

import static currentBot.Controllers.PlayerConstants.*;

/**
 * New strategy since 14/01/2020:
 * > get soup
 * > landscapers
 * > drone defence
 * > drone attack (muddle up enemy formations)
 */
public class DeliveryDroneControllerMk2 extends Controller {

    enum State {
        DEFEND,
        ATTACK,
        WANDER
    }

    State currentState = null;

    MapLocation nearestWaterTile;

    public DeliveryDroneControllerMk2(RobotController rc) {
        // Do heavy computation stuff here as 10 rounds are spent being built
        containsWater = new Boolean[rc.getMapHeight()][rc.getMapWidth()];
        queue = new RingQueue<>(rc.getMapHeight() * rc.getMapWidth());
        getInfo(rc);
    }

    public void searchSurroundingsContinued() throws GameActionException {
        // Yep bad naming on my part, searchSurroundings() is used in miner but has a return type
        for (int dx = -4; dx <= 4; ++dx) {
            for (int dy = -4; dy <= 4; ++dy) {
                MapLocation delta = new MapLocation(dx, dy);
                MapLocation sensePos = new MapLocation(
                        rc.getLocation().x + dx,
                        rc.getLocation().y + dy);

                if (!rc.canSenseLocation(sensePos)) continue;

                containsWater[sensePos.y][sensePos.x] = rc.senseFlooding(sensePos);
            }
        }
    }

    public void run() throws GameActionException {
        if (!rc.isReady()) {
            searchSurroundingsContinued();
            return;
        }



        assignRole();
        hqInfo(); // includes scanning robots

        if (!rc.isCurrentlyHoldingUnit()) {
            switch (currentState) {
                case ATTACK: execAttackPatrol();            break;
                case DEFEND: execDefendPatrol();            break;
                case WANDER: execWanderPatrol();            break;
            }
        } else {
            execKill();
        }
    }

    public void assignRole() throws GameActionException {

        /*
            Role assignment depending on turn. Early game defend, late game attack.
         */

        if (rc.getRoundNum() >= SWITCH_TO_ATTACK) {
            switchToAttackMode();
        } else {
//            switchToDefenceMode();
            switchToWanderMode();
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

        // camp around home
        if (ADJACENT_DEFEND ?
                isAdjacentTo(allyHQ) :
                rc.getLocation().isWithinDistanceSquared(allyHQ, DEFENSE_RADIUS)) {
            System.out.println("stand still to defend");
        } else {
            if (allyHQ != null) {
                if (!tryMove(rc.getLocation().directionTo(allyHQ))) {
                    tryMove(randomDirection());
                }
            } else {
                tryMove(randomDirection()); // should never get here since should find hq
            }
            System.out.println("move to defend");
        }
    }

    public void execAttackPatrol() throws GameActionException {

        /*
            Stay in perimeter around enemy base and pick up nearby enemy units.
            Should not try to chase enemies to pick them up because of cooldowns
         */

        // trying to pick up enemies
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, ENEMY);
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
                tryMove(movementSolver.droneDirectionToGoal(enemyHQ));
            }
        } else if (allyHQ != null) { // move away from own hq
            if (movementSolver.nearEdge()) {
                favourableDirection = favourableDirection.opposite();
            }
            tryMove(movementSolver.directionGo(favourableDirection));
            System.out.println("moving away from home hq");
        } else {
            tryMove(randomDirection());
        }
    }

    public void execWanderPatrol() throws GameActionException {
        for (RobotInfo enemy : enemies) {
            if (tryPickUpUnit(enemy)) {
                return;
            }
        }

        movementSolver.windowsRoam();
    }

    public void switchToAttackMode() throws GameActionException {
        currentState = State.ATTACK;
    }

    public void switchToDefenceMode() throws GameActionException {
        currentState = State.DEFEND;
    }

    public void switchToWanderMode() throws GameActionException {
        currentState = State.WANDER;
    }

    public void execKill() throws GameActionException {

        /*
            move randomly until find water and drop unit // TODO improve like remembering water locations
         */
        if (nearestWaterTile == null) {
            System.out.println("Looking for water tile");
            nearestWaterTile = getNearestWaterTile();
        }

        if (!isAdjacentTo(nearestWaterTile)) {
            System.out.println("Moving to water tile");
            tryMove(movementSolver.droneDirectionToGoal(nearestWaterTile));
        } else {
            System.out.println("dropping in water tile");
            if (rc.canDropUnit(rc.getLocation().directionTo(nearestWaterTile))) {
                rc.dropUnit(rc.getLocation().directionTo(nearestWaterTile));
                nearestWaterTile = null; // look for different water tile next time
            }
        }
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
