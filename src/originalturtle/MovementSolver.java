package originalturtle;
import battlecode.common.*;

/*
    A class that handles movement
 */

public class MovementSolver {
    MapLocation previous;
    Direction getGoalDirection(RobotController rc, MapLocation from, MapLocation goal) throws GameActionException {
        Direction dir = from.directionTo(goal);
        int changes = 0;
        // while obstacle ahead, keep rotating
        while (isObstacle(rc, dir, from.add(dir))) {
            dir = dir.rotateLeft();
            changes++;
            // if blocked in every direction, stop rotating
            if (changes > 8) return Direction.CENTER;
        }
        previous = from;
        return dir;
    }

    boolean isObstacle(RobotController rc, Direction dir, MapLocation to) throws GameActionException {
        //point is obstacle if there is a building, is not on map (checked by canMove)
        // if it is flooded, or is previous point
        return !rc.canMove(dir) || rc.senseFlooding(to) || to == previous;
    }
}
