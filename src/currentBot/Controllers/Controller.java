package currentBot.Controllers;

/*
    So I thought we would do a controller thing like this:
    https://github.com/j-mao/battlecode-2019/blob/master/newstart/MyRobot.java
*/

import battlecode.common.*;
import currentBot.CommunicationHandler;
import currentBot.MovementSolver;

import java.util.LinkedList;
import java.util.List;

public abstract class Controller {
    public RobotController rc = null;

    public Team NEUTRAL = Team.NEUTRAL;
    public Team ALLY;
    public Team ENEMY;

    public MapLocation allyHQ  = null; // to be filled out by a blockchain message
    public MapLocation enemyHQ = null;

    public RobotInfo[] enemies;
    public RobotInfo[] allies;

    public CommunicationHandler communicationHandler;
    public MovementSolver movementSolver;

    public MapLocation spawnPoint;         // loc of production
    public MapLocation spawnBase;          // loc of building which produced this unit (relevant for units)
    public Direction spawnBaseDirFrom;     // dir FROM building which produced this unit
    public Direction spawnBaseDirTo;       // dir TO building which produced this unit

    public int spawnTurn;

    enum Symmetry {
        HORIZONTAL,
        VERTICAL,
        ROTATIONAL
    }

    Direction[] directions = {
            Direction.NORTH,
            Direction.NORTHEAST,
            Direction.EAST,
            Direction.SOUTHEAST,
            Direction.SOUTH,
            Direction.SOUTHWEST,
            Direction.WEST,
            Direction.NORTHWEST
    };

    Direction[] cardinal = {directions[0], directions[2], directions[4], directions[6]};
    Direction[] ordinal  = {directions[1], directions[3], directions[5], directions[7]};

    public void getInfo(RobotController rc) {
        ALLY = rc.getTeam();
        ENEMY = rc.getTeam().opponent();
        this.rc = rc;
        this.communicationHandler = new CommunicationHandler(rc);
        this.movementSolver = new MovementSolver(rc);
        this.spawnTurn = rc.getRoundNum();
        this.spawnPoint = rc.getLocation();
        getSpawnBase();
        getHQInfo();
    }

    boolean tryBuild(RobotType type, Direction dir) throws GameActionException {
        if (rc.isReady() && rc.canBuildRobot(type, dir)) {
            rc.buildRobot(type, dir);
            return true;
        } else return false;
    }

    boolean tryMine(Direction dir) throws GameActionException {
        if (rc.isReady() && rc.canMineSoup(dir)) {
            rc.mineSoup(dir);
            return true;
        } else return false;
    }

    boolean tryMove(Direction dir) throws GameActionException {
        // System.out.println("I am trying to move " + dir + "; " + rc.isReady() + " " + rc.getCooldownTurns() + " " + rc.canMove(dir));
        if (rc.isReady() && rc.canMove(dir)) {
            rc.move(dir);
            return true;
        } else return false;
    }

    void goToLocationToSense(MapLocation goal) throws GameActionException {
        while (!rc.canSenseLocation(goal)) {
            if (tryMove(movementSolver.directionToGoal(goal))) Clock.yield();
        }
    }

    void goToLocationToDeposit(MapLocation goal) throws GameActionException {
        while ( rc.getLocation().distanceSquaredTo(goal) > 1
                || !rc.canDepositDirt(rc.getLocation().directionTo(goal)) ) {
            if (tryMove(movementSolver.directionToGoal(goal))) Clock.yield();
        }
    }

    void tryBlockchain() throws GameActionException {
        int[] message = new int[7];
        for (int i = 0; i < 7; i++) {
            message[i] = 123;
        }
        if (rc.canSubmitTransaction(message, 10))
            rc.submitTransaction(message, 10);
    }

    boolean tryRefine(Direction dir) throws GameActionException {
        if (rc.isReady() && rc.canDepositSoup(dir)) {
            rc.depositSoup(dir, rc.getSoupCarrying());
            return true;
        } else return false;
    }

    public boolean tryShoot(RobotInfo robotInfo) throws GameActionException {
        if (rc.canShootUnit(robotInfo.getID())) {
            rc.shootUnit(robotInfo.getID());
            System.out.println("shot down enemy");
            return true;
        }
        return false;
    }

    Direction randomDirection() {
        return directions[(int) (Math.random() * directions.length)];
    }

    int getDistanceSquared(MapLocation p1, MapLocation p2) {
        return (p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y);
    }

    int getDistanceFrom(RobotInfo other) {
        return (rc.getLocation().x - other.getLocation().x) * (rc.getLocation().x - other.getLocation().x)
                + (rc.getLocation().y - other.getLocation().y) * (rc.getLocation().y - other.getLocation().y);
    }

    int getDistanceFrom(MapLocation other) {
        return (rc.getLocation().x - other.x) * (rc.getLocation().x - other.x)
                + (rc.getLocation().y - other.y) * (rc.getLocation().y - other.y);
    }

    boolean isAdjacentTo(RobotInfo other) {
        return getDistanceFrom(other) == 1;
    }

    boolean isAdjacentTo(MapLocation loc) {
        return getDistanceFrom(loc) == 1;
    }

    MapLocation adjacentTile(Direction dir) {
        return rc.getLocation().add(dir);
    }

    List<MapLocation> tilesInRange() {
        List<MapLocation> tiles = new LinkedList<>();
        int rsq = rc.getCurrentSensorRadiusSquared();
        int r = 1; while (r*r < rsq) r++;
        int x = rc.getLocation().x; int y = rc.getLocation().y;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                MapLocation pos = new MapLocation(x+dx,y+dy);
                // apparently doesn't check if on map
                if (x+dx < 0 || x + dx >= rc.getMapWidth() || y+dy < 0 || y+dy >= rc.getMapHeight()) continue;
                if (rc.canSenseLocation(pos)) { // within range and on map
                    tiles.add(pos);
                }
            }
        }
        return tiles;
    }


    public void getSpawnBase() {

        /*
            Information for units regarding production location, source (building) and direction
            assumes only one adjacent production source
        */

        RobotType spawnType;
        switch (rc.getType()) {
            case MINER: spawnType = RobotType.HQ; break;
            case DELIVERY_DRONE: spawnType = RobotType.FULFILLMENT_CENTER; break;
            case LANDSCAPER: spawnType = RobotType.DESIGN_SCHOOL; break;
            default: return; // ignore if robot is a building
        }
        MapLocation loc = rc.getLocation();
        for (Direction dir : directions) {
            RobotInfo robotAt = null;
            try {
                robotAt = rc.senseRobotAtLocation(loc.add(dir));
            } catch (GameActionException e) {
                e.printStackTrace();
            }
            if (robotAt != null && robotAt.getTeam().equals(rc.getTeam()) && robotAt.getType().equals(spawnType)) {
                System.out.println("found spawn base");
                spawnBase = loc.add(dir);
                spawnBaseDirTo = dir;
                spawnBaseDirFrom = dir.opposite();
            }
        }
    }

    public void hqInfo() throws GameActionException {
        scanRobots();
        tryFindHomeHQLoc();
        tryFindEnemyHQLoc();
        getHQInfo();
    }

    public void scanRobots() throws GameActionException {
//        enemies = rc.senseNearbyRobots();
        System.out.println("scan radius is "+rc.getCurrentSensorRadiusSquared()+", pollution is "+rc.sensePollution(rc.getLocation()));
        enemies = rc.senseNearbyRobots(-1, ENEMY);
        allies  = rc.senseNearbyRobots(-1, ALLY);
    }

    public void tryFindHomeHQLoc() {
        if (allyHQ != null) return;
        System.out.println("trying to find ally HQ out of "+allies.length+" options");
        for (RobotInfo ally : allies) {
            System.out.println("found home HQ location");
            if (ally.getType() == RobotType.HQ && ally.getTeam() == ALLY) {
                allyHQ = ally.getLocation();
                return;
            }
        }
    }

    public void tryFindEnemyHQLoc() throws GameActionException {
        if (enemyHQ != null) return;
        System.out.println("trying to find enemy HQ out of "+enemies.length+" options");
        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == RobotType.HQ && enemy.getTeam() == ENEMY) {
                System.out.println("found enemy HQ location");
                enemyHQ = enemy.getLocation();
                communicationHandler.sendEnemyHQLoc(enemyHQ);
                return;
            }
        }
    }

    public void getHQInfo() {
        if (rc.getRoundNum() == 1) return;
        if (allyHQ == null) {
            try {
                allyHQ = communicationHandler.receiveAllyHQLoc();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (enemyHQ == null) {
            try {
                enemyHQ = communicationHandler.receiveEnemyHQLoc();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public boolean onTheMap(MapLocation pos) {
        return (0 <= pos.x && pos.x < rc.getMapWidth() && 0 <= pos.y && pos.y < rc.getMapHeight());
    }

    abstract public void run() throws GameActionException;
}
