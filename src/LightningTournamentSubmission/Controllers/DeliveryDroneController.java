package lightningTournamentSubmission.Controllers;

import battlecode.common.*;

public class DeliveryDroneController extends Controller {
    private static final int SENSOR_RADIUS = 24;
    private static final int CHASE_RADIUS = 24;
    private static final int SWITCH_TO_ATTACK = 300; // turn for switching to attack mode

    enum State {
        COW,
        SCOUTER,
        DEFEND,
        ATTACK
    }

    State currentState = null;

    MapLocation target;

    public DeliveryDroneController(RobotController rc) {
        getInfo(rc);
    }


    public void run() throws GameActionException {
        if (!rc.isReady()) return;

        assignRole();

        // TODO reimplement communication handling
        if (allyHQ == null) allyHQ = communicationHandler.receiveAllyHQLoc();
        if (enemyHQ == null) enemyHQ = communicationHandler.receiveEnemyHQLoc();

        if (!rc.isCurrentlyHoldingUnit()) {
            switch (currentState) {
                case SCOUTER: execScout();                  break;
                case ATTACK: execAttackPatrol();            break;
                case DEFEND: execDefendPatrol();            break;
                case COW: execSearchCow();                  break;
            }
        } else {
            switch (currentState) {
                case ATTACK: execAttackKill();              break;
                case DEFEND: execDefendKill();              break;
                case COW: execDropCow();                    break;
            }
        }
    }

    public void assignRole() throws GameActionException {

        /*
            Role assignment depending on turn
         */

        if (currentState != null) return;

        // FIXME : the communication implementation for this is yuck
        target = communicationHandler.receiveScoutLocation(spawnTurn);
        if (target != null) { // scout, with scout location at variable target
            switchToScoutMode();
        } else { // defend, with post at variable target
            switchToDefenceMode();
        }
    }

    public void execScout() throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(SENSOR_RADIUS, ENEMY);
        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == RobotType.HQ) {
                communicationHandler.sendEnemyHQLoc(enemy.location);
                switchToDefenceMode();
            }
        }

        if (rc.getLocation().isWithinDistanceSquared(target, 2)) {
            System.out.println("reached target loc");
            // TODO: send message for not finding hq
            switchToDefenceMode();
        } else {
            tryMove(movementSolver.directionToGoal(target));
        }
    }

    public void execDefendPatrol() throws GameActionException {

        /*
            Stay in perimeter around base and chase and pick up nearby enemy units
         */

        RobotInfo[] enemyScapers = rc.senseNearbyRobots(CHASE_RADIUS, ENEMY);
        for (RobotInfo enemy : enemyScapers) {
            if (enemy.type == RobotType.LANDSCAPER || enemy.type == RobotType.MINER) {
                if (!tryPickUpUnit(enemy)) {
                    System.out.println("moving towards enemy");
                    tryMove(movementSolver.directionToGoal(rc.getLocation(), enemy.getLocation()));
                }
                return;
            }
        }

        moveToTargetAndStay();
    }

    public void execDefendKill() throws GameActionException {
        execKill();
    }

    public void execSearchCow() throws GameActionException {

        /*
            move randomly until find cow to pick up
         */

        RobotInfo[] cows = rc.senseNearbyRobots(SENSOR_RADIUS, NEUTRAL);
        for (RobotInfo cow : cows) {
            if (true) { // TODO: heuristic for when to pick up cow, i.e. when far from enemy HQ and close to own HQ
                if (!tryPickUpUnit(cow)) {
                    tryMove(movementSolver.directionToGoal(rc.getLocation(), cow.getLocation()));
                }
            }
        }
        tryMove(randomDirection()); // TODO
    }

    public void execDropCow() throws GameActionException {

        /*
            Drop cow when enemy building is closer to current location than closest ally building // FIXME modify to do the other cow strategy
         */

        RobotInfo[] enemies = rc.senseNearbyRobots(SENSOR_RADIUS, ENEMY);
        RobotInfo[] allies = rc.senseNearbyRobots(SENSOR_RADIUS, ALLY);

        int distanceFromAlly = SENSOR_RADIUS + 1;
        for (RobotInfo ally : allies) {
            distanceFromAlly = Math.min(distanceFromAlly, getDistanceFrom(ally));
        }

        for (RobotInfo enemy : enemies) {
            if (enemy.type.isBuilding() && getDistanceFrom(enemy) - distanceFromAlly > 2) {
                for (Direction dir : directions) {
                    if (rc.canDropUnit(dir) && rc.canSenseLocation(adjacentTile(dir)) && rc.senseFlooding(adjacentTile(dir))) {
                        rc.dropUnit(dir);
                        System.out.println("plop: dropped cow");
                        return;
                    }
                }
            }
        }

        // failed to drop unit
        moveTowardsEnemy();
    }

    public void execAttackPatrol() throws GameActionException {
        // TODO
    }

    public void execAttackKill() throws GameActionException {
        execKill();
    }

    public void switchToScoutMode() throws GameActionException {
        currentState = State.SCOUTER;
        System.out.println("Role is to scout direction "+(Math.abs(spawnPoint.y - target.y) <= 3?"horizontally":"vertically"));
    }

    public void switchToAttackMode() throws GameActionException {
        // TODO
    }

    public void switchToDefenceMode() throws GameActionException {
        if (allyHQ != null) {
            target = new MapLocation(
                    allyHQ.x + (spawnBaseDirFrom.getDeltaX() * 4),
                    allyHQ.y + (spawnBaseDirFrom.getDeltaY() * 4)
            );
        } else {
            target = new MapLocation(
                    spawnBase.x + (spawnBaseDirFrom.getDeltaX() * 4),
                    spawnBase.y + (spawnBaseDirFrom.getDeltaY() * 4)
            );
        }
        System.out.println("Role is to defend");
        currentState = State.DEFEND;
    }

    public void moveTowardsEnemy() throws GameActionException {

        /*
            move towards enemy HQ location is known, otherwise move away from own HQ
         */

        RobotInfo[] enemies = rc.senseNearbyRobots(SENSOR_RADIUS, ENEMY);

        if (enemies.length > 0) {
            tryMove(movementSolver.directionToGoal(rc.getLocation(), enemies[0].getLocation()));
        } else {
            if (enemyHQ != null) { // move towards enemy hq
                tryMove(movementSolver.directionToGoal(rc.getLocation(), enemyHQ));
                System.out.println("moving directly to enemy");
            } else if (allyHQ != null) { // move away from own hq
                tryMove(movementSolver.directionToGoal(allyHQ, rc.getLocation()));
            } else {
                tryMove(randomDirection());
            }
        }
    }

    public void moveToTargetAndStay() throws GameActionException {
        MapLocation mapLocation = rc.getLocation();
        if (!mapLocation.equals(target))
            tryMove(movementSolver.directionToGoal(target));

    }

    public void execKill() throws GameActionException {

        /*
            move randomly until find water and drop unit // TODO improve
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
