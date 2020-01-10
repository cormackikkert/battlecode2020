package originalturtle.Controllers;

import battlecode.common.*;

public class DeliveryDroneController extends Controller {
    private final int SENSOR_RADIUS = 24;
    Team NEUTRAL = Team.NEUTRAL;
    Team ALLY = rc.getTeam();
    Team ENEMY = rc.getTeam().opponent();

    enum State { // (A for ally, E for enemy) TODO: decide when to switch roles
        COW,
        AMINER,
        ASCAPER,
        EMINER,
        ESCAPER,
    }

    State currentState = State.COW;

    public DeliveryDroneController(RobotController rc) {
        this.rc = rc;
    }

    public void run() throws GameActionException {
        if (!rc.isCurrentlyHoldingUnit()) {
            switch (currentState) {
                case COW: execSearchCow();                  break;
                case AMINER: execSearchAllyMiner();         break;
                case ASCAPER: execSearchAllyScaper();       break;
                case EMINER: execSearchEnemyMiner();        break;
                case ESCAPER: execSearchEnemyScaper();      break;
            }
        } else {
            switch (currentState) {
                case COW: execDropCow();                    break;
                case AMINER: execDropAllyMiner();           break;
                case ASCAPER: execDropAllyScaper();         break;
                case EMINER: execDropEnemyMiner();          break;
                case ESCAPER: execDropEnemyScaper();        break;
            }
        }
    }

    public void execSearchCow() throws GameActionException {
        RobotInfo[] cows = rc.senseNearbyRobots(SENSOR_RADIUS, NEUTRAL);
        for (RobotInfo cow : cows) {
            if (true) { // TODO: heuristic for when to pick up cow, i.e. when far from enemy HQ and close to own HQ
                if (isAdjacentTo(cow)) {
                    rc.pickUpUnit(cow.getID());
                } else {
                    tryMove(moveGreedy(rc.getLocation(), cow.getLocation()));
                }
                return;
            }
        }
        tryMove(randomDirection()); // TODO
    }

    public void execSearchAllyMiner() throws GameActionException {
        RobotInfo[] allyMiners = rc.senseNearbyRobots(SENSOR_RADIUS, ALLY);
        for (RobotInfo miner : allyMiners) {
            if (miner.type == RobotType.MINER) { // TODO: heuristic for when to pick up
                if (isAdjacentTo(miner)) {
                    rc.pickUpUnit(miner.getID());
                } else {
                    tryMove(moveGreedy(rc.getLocation(), miner.getLocation()));
                }
                return;
            }
        }
        tryMove(randomDirection()); // TODO
    }

    public void execSearchAllyScaper() throws GameActionException {
        RobotInfo[] allyScaper = rc.senseNearbyRobots(SENSOR_RADIUS, ALLY);
        for (RobotInfo scaper : allyScaper) {
            if (scaper.type == RobotType.LANDSCAPER) { // TODO: heuristic for when to pick up
                if (isAdjacentTo(scaper)) {
                    rc.pickUpUnit(scaper.getID());
                } else {
                    tryMove(moveGreedy(rc.getLocation(), scaper.getLocation()));
                }
                return;
            }
        }
        tryMove(randomDirection()); // TODO
    }

    public void execSearchEnemyMiner() throws GameActionException {
        RobotInfo[] enemyMiners = rc.senseNearbyRobots(SENSOR_RADIUS, ENEMY);
        for (RobotInfo miner : enemyMiners) {
            if (miner.type == RobotType.MINER) { // TODO: heuristic for when to pick up
                if (isAdjacentTo(miner)) {
                    rc.pickUpUnit(miner.getID());
                } else {
                    tryMove(moveGreedy(rc.getLocation(), miner.getLocation()));
                }
                return;
            }
        }
        tryMove(randomDirection()); // TODO
    }

    public void execSearchEnemyScaper() throws GameActionException {
        RobotInfo[] enemyScapers = rc.senseNearbyRobots(SENSOR_RADIUS, ENEMY);
        for (RobotInfo scaper : enemyScapers) {
            if (scaper.type == RobotType.LANDSCAPER) { // TODO: heuristic for when to pick up
                if (isAdjacentTo(scaper)) {
                    rc.pickUpUnit(scaper.getID());
                } else {
                    tryMove(moveGreedy(rc.getLocation(), scaper.getLocation()));
                }
                return;
            }
        }
        tryMove(randomDirection()); // TODO
    }

    /* Naive cow dropping strategy:
            Move away from own HQ and towards enemy HQ
            Drop cow when enemy building is closer to current location than closest ally building
            Ignores net guns for now, since cows are dropped at location where shot down
     */
    public void execDropCow() throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(SENSOR_RADIUS, ENEMY);
        RobotInfo[] allies = rc.senseNearbyRobots(SENSOR_RADIUS, ALLY);

        int distanceFromAlly = SENSOR_RADIUS + 1;
        for (RobotInfo ally : allies) {
            distanceFromAlly = Math.min(distanceFromAlly, getDistanceFrom(ally));
        }

        boolean drop = false;
        droppings : for (RobotInfo enemy : enemies) {
            if (enemy.type.isBuilding() && getDistanceFrom(enemy) - distanceFromAlly > 2) {
                for (Direction dir : directions) {
                    if (rc.canDropUnit(dir)) {
                        rc.dropUnit(dir);
                        drop = true;
                        break droppings;
                    }
                }
            }
        }
        if (!drop) {
            if (enemyHQ != null) { // move towards enemy hq
                moveGreedy(rc.getLocation(), enemyHQ);
            } else if (allyHQ != null) { // move away from own hq
                moveGreedy(allyHQ, rc.getLocation());
            } else {
                tryMove(randomDirection());
            }
        }
    }

    public void execDropAllyMiner() throws GameActionException {

    }

    public void execDropAllyScaper() throws GameActionException {

    }

    public void execDropEnemyMiner() throws GameActionException {

    }

    public void execDropEnemyScaper() throws GameActionException {

    }
}
