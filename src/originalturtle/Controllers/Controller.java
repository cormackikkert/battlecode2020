package originalturtle.Controllers;

/*
    So I thought we would do a controller thing like this:
    https://github.com/j-mao/battlecode-2019/blob/master/newstart/MyRobot.java
*/

import battlecode.common.*;
import originalturtle.CommunicationHandler;
import originalturtle.MovementSolver;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public abstract class Controller {
    MapLocation allyHQ = null; // to be filled out by a blockchain message
    MapLocation enemyHQ = null;

    CommunicationHandler communicationHandler;
    MovementSolver movementSolver;

    RobotController rc = null;

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

    Direction randomDirection() {
        return directions[(int) (Math.random() * directions.length)];
    }

    Direction moveGreedy(MapLocation from, MapLocation to) {
        // Turns out this already exists
        // TODO: remove
        if (from.x < to.x && from.y < to.y)   return Direction.NORTHEAST;
        if (from.x < to.x && from.y == to.y)  return Direction.EAST;
        if (from.x < to.x && from.y > to.y)   return Direction.SOUTHEAST;
        if (from.x == to.x && from.y < to.y)  return Direction.NORTH;
        if (from.x == to.x && from.y > to.y)  return Direction.SOUTH;
        if (from.x > to.x && from.y < to.y)   return Direction.NORTHWEST;
        if (from.x > to.x && from.y == to.y)  return Direction.WEST;
        if (from.x > to.x && from.y > to.y)   return Direction.SOUTHWEST;
        return Direction.CENTER;
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

    List<MapLocation> adjacentTiles() {
        List<MapLocation> out = new ArrayList<>();
        MapLocation curr = rc.getLocation();
        for (Direction dir : directions) {
            MapLocation adj = curr.add(dir);
            if (!rc.canSenseLocation(adj)) continue;
            out.add(adj);
        }
        return out;
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


    Direction getAdjacentDirection(MapLocation loc) { // assuming adjacency
        if (!isAdjacentTo(loc)) return null;
        int x1 = rc.getLocation().x; int x2 = loc.x;
        int y1 = rc.getLocation().y; int y2 = loc.y;
        if (x1 == x2 && y1 < y2)        return Direction.NORTH;
        if (x1 == x2 && y1 > y2)        return Direction.SOUTH;
        if (x1 > x2 && y1 < y2)         return Direction.NORTHWEST;
        if (x1 > x2 && y1 == y2)        return Direction.WEST;
        if (x1 > x2 && y1 > y2)         return Direction.SOUTHWEST;
        if (x1 < x2 && y1 < y2)         return Direction.NORTHEAST;
        if (x1 < x2 && y1 == y2)        return Direction.EAST;
        if (x1 < x2 && y1 > y2)         return Direction.SOUTHEAST;
        return Direction.CENTER;
    }

    abstract public void run() throws GameActionException;
}
