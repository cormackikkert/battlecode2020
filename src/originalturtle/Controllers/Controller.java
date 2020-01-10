package originalturtle.Controllers;

/*
    So I thought we would do a controller thing like this:
    https://github.com/j-mao/battlecode-2019/blob/master/newstart/MyRobot.java
*/

import battlecode.common.*;

public abstract class Controller {
    MapLocation allyHQ = null; // to be filled out by a blockchain message
    MapLocation enemyHQ = null;

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

    boolean isAdjacentTo(RobotInfo other) {
        return getDistanceFrom(other) == 1;
    }

    abstract public void run() throws GameActionException;
}
