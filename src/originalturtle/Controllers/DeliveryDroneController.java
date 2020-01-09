package originalturtle.Controllers;

import battlecode.common.*;
import originalturtle.GameUtil;

public class DeliveryDroneController extends Controller { // TODO: maybe split into two classes? one for search, one for picking up
    private final int SENSOR_RADIUS = 24;
    enum State { // role is to search for and move around... (A for ally, E for enemy) TODO: decide when to switch roles
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
        Team enemy = rc.getTeam().opponent();
        if (!rc.isCurrentlyHoldingUnit()) {
            // See if there are any enemy robots within striking range (distance 1 from lumberjack's radius)
            RobotInfo[] robots = rc.senseNearbyRobots(GameConstants.DELIVERY_DRONE_PICKUP_RADIUS_SQUARED, enemy);

            if (robots.length > 0) {
                // Pick up a first robot within range
                rc.pickUpUnit(robots[0].getID());
                System.out.println("I picked up " + robots[0].getID() + "!");
            }

            switch (currentState) {
                case COW: execSearchCow();                  break;
                case AMINER: execSearchAllyMiner();         break;
                case ASCAPER: execSearchAllyScaper();       break;
                case EMINER: execSearchEnemyMiner();        break;
                case ESCAPER: execSearchEnemyScaper();      break;
            }
        } else { // is holding a unit
            tryMove(randomDirection()); // TODO
        }
    }

    public void execSearchCow() throws GameActionException {
        RobotInfo[] cows = rc.senseNearbyRobots(SENSOR_RADIUS, Team.NEUTRAL);
        for (RobotInfo cow : cows) {
            if (true) { // TODO: heuristic for when to pick up cow, i.e. when far from enemy HQ and close to own HQ
                if (GameUtil.isAdjacent(cow, rc)) {
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
        RobotInfo[] allyMiners = rc.senseNearbyRobots(SENSOR_RADIUS, rc.getTeam());
        for (RobotInfo miner : allyMiners) {
            if (miner.type == RobotType.MINER) { // TODO: heuristic for when to pick up
                if (GameUtil.isAdjacent(miner, rc)) {
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
        RobotInfo[] allyScaper = rc.senseNearbyRobots(SENSOR_RADIUS, rc.getTeam());
        for (RobotInfo scaper : allyScaper) {
            if (scaper.type == RobotType.MINER) { // TODO: heuristic for when to pick up
                if (GameUtil.isAdjacent(scaper, rc)) {
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
        RobotInfo[] enemyMiners = rc.senseNearbyRobots(SENSOR_RADIUS, rc.getTeam());
        for (RobotInfo miner : enemyMiners) {
            if (miner.type == RobotType.MINER) { // TODO: heuristic for when to pick up
                if (GameUtil.isAdjacent(miner, rc)) {
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
        RobotInfo[] enemyScapers = rc.senseNearbyRobots(SENSOR_RADIUS, rc.getTeam());
        for (RobotInfo scaper : enemyScapers) {
            if (scaper.type == RobotType.MINER) { // TODO: heuristic for when to pick up
                if (GameUtil.isAdjacent(scaper, rc)) {
                    rc.pickUpUnit(scaper.getID());
                } else {
                    tryMove(moveGreedy(rc.getLocation(), scaper.getLocation()));
                }
                return;
            }
        }
        tryMove(randomDirection()); // TODO
    }
}
