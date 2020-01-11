package originalturtle.Controllers;

import battlecode.common.*;
import originalturtle.CommunicationHandler;
import originalturtle.MovementSolver;

public class DeliveryDroneController extends Controller {
    private final int SENSOR_RADIUS = 24;

    Team NEUTRAL = Team.NEUTRAL;
    Team ALLY;
    Team ENEMY;

    enum State { // (A for ally, E for enemy) TODO: decide when to switch roles
        COW,
        AMINER,
        ASCAPER,
        EMINER,
        ESCAPER,
        SCOUTER
    }

    State currentState = null;

    MovementSolver movementSolver;

    public DeliveryDroneController(RobotController rc) {
        this.rc = rc;
        this.communicationHandler = new CommunicationHandler(rc);
        this.movementSolver = new MovementSolver(rc);
        ALLY = rc.getTeam();
        ENEMY = rc.getTeam().opponent();
        System.out.println("knock knock, Pizza delivery");
    }

    public void run() throws GameActionException {
        assignRole();

        if (!rc.isReady()) return;

        if (allyHQ == null) allyHQ = communicationHandler.receiveAllyHQLoc();
        if (enemyHQ == null) enemyHQ = communicationHandler.receiveEnemyHQLoc();

        if (!rc.isCurrentlyHoldingUnit()) {
            switch (currentState) {
                case COW: execSearchCow();                  break;
                case AMINER: execSearchAllyMiner();         break;
                case ASCAPER: execSearchAllyScaper();       break;
                case EMINER: execSearchEnemyMiner();        break;
                case ESCAPER: execSearchEnemyScaper();      break;
                case SCOUTER: execScout();                  break;
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

    int[] diagX = {1, 1, -1, -1};
    int[] diagY = {1, -1, 1, -1};

    int[] strX = {0, 1, 0, -1};
    int[] strY = {1, 0, -1, 0};

    public void assignRole() throws GameActionException {
        if (currentState != null) return;

        int x = rc.getLocation().x;
        int y = rc.getLocation().y;

        int HSize = this.rc.getMapHeight();
        int WSize = this.rc.getMapWidth();

        for (int i = 0; i < 4; i++) { // scouters are spawned either N, W, E, S of fulfillment center and not in diagonal dir
            RobotInfo straight = rc.senseRobotAtLocation(new MapLocation(x + strX[i], y + strY[i]));
            if (straight == null) continue;
            System.out.println("buildingis "+straight.getType());
            if (straight.getType() == RobotType.FULFILLMENT_CENTER && straight.getTeam() == rc.getTeam()) {
                currentState = State.SCOUTER;
                // opposite direction
                if (i == 0) {dir = Direction.SOUTH;     edge = new MapLocation(x, 0);}
                if (i == 1) {dir = Direction.WEST;      edge = new MapLocation(0, y);}
                if (i == 2) {dir = Direction.NORTH;     edge = new MapLocation(x, HSize);}
                if (i == 3) {dir = Direction.EAST;      edge = new MapLocation(WSize, y);}
                System.out.println("scouting time");
                return;
            }
        }

        currentState = State.EMINER;
    }

    Direction dir;
    MapLocation edge;
    public void execScout() throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(SENSOR_RADIUS, ENEMY);
        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == RobotType.HQ) {
                communicationHandler.sendEnemyHQLoc(enemy.location);
                currentState = State.EMINER;
            }
        }

        if (rc.getLocation().equals(edge)) {
            currentState = State.EMINER;
        } else {
            tryMove(movementSolver.droneDirectionToGoal(edge));
        }
    }

    public void execSearchCow() throws GameActionException {
        RobotInfo[] cows = rc.senseNearbyRobots(SENSOR_RADIUS, NEUTRAL);
        for (RobotInfo cow : cows) {
            if (true) { // TODO: heuristic for when to pick up cow, i.e. when far from enemy HQ and close to own HQ
                if (isAdjacentTo(cow)) {
                    rc.pickUpUnit(cow.getID());
                    System.out.println("picked up cow");
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
        if (allyHQ != null)
            tryMove(moveGreedy(allyHQ, rc.getLocation())); // move away from HQ
        else
            tryMove(randomDirection());
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

    /* Current cow dropping strategy:
            Move away from own HQ and towards enemy HQ
            Drop cow when enemy building is closer to current location than closest ally building
            Ignores net guns for now, since cows are dropped at location where shot down
     */
    public void execDropCow() throws GameActionException {// FIXME : not working, not detecting nearby enemies
        RobotInfo[] enemies = rc.senseNearbyRobots(SENSOR_RADIUS, ENEMY);
        RobotInfo[] allies = rc.senseNearbyRobots(SENSOR_RADIUS, ALLY);

//        System.out.println("nearby: "+enemies.length);

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

    /* Current ally dropping strategy:
            Used for attacking enemy HQ
            Miners can build net guns
            Landscapers can attack buildings
            Path of attack is straight towards HQ, can consider other approaches such as pincer attack etc.
     */
    public void execDropAllyMiner() throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(SENSOR_RADIUS, ENEMY);

        // try to drop unit
        for (RobotInfo enemy : enemies) {
            if (enemy.type == RobotType.HQ) {
                for (Direction dir : directions) {
                    if (rc.canDropUnit(dir)) {
                        rc.dropUnit(dir);
                        return;
                    }
                }
            }
        }

        // fail to drop unit
        moveTowardsEnemy();
    }

    public void execDropAllyScaper() throws GameActionException {

    }

    /* Current enemy dropping strategy:
            Either kill unit by dropping into water
            Stall and try not to die
     */
    public void execDropEnemyMiner() throws GameActionException {
        for (Direction dir : directions) {
            if (rc.canDropUnit(dir) && rc.canSenseLocation(adjacentTile(dir)) && rc.senseFlooding(adjacentTile(dir))) {
                rc.dropUnit(dir);
                return;
            }
        }
        tryMove(randomDirection());
    }

    public void execDropEnemyScaper() throws GameActionException {
        for (Direction dir : directions) {
            if (rc.canDropUnit(dir) && rc.canSenseLocation(adjacentTile(dir)) && rc.senseFlooding(adjacentTile(dir))) {
                rc.dropUnit(dir);
                return;
            }
        }
        tryMove(randomDirection());
    }

    public void moveTowardsEnemy() throws GameActionException {
        boolean move = false;
        RobotInfo[] enemies = rc.senseNearbyRobots(SENSOR_RADIUS, ENEMY);

        if (enemies.length > 0) {
            move = tryMove(moveGreedy(rc.getLocation(), enemies[0].getLocation()));
        } else {
            if (enemyHQ != null) { // move towards enemy hq
                move = tryMove(moveGreedy(rc.getLocation(), enemyHQ));
            } else if (allyHQ != null) { // move away from own hq
                move = tryMove(moveGreedy(allyHQ, rc.getLocation()));
            }
        }

        if (!move) {
            tryMove(randomDirection());
        }
    }
}
