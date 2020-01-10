package originalturtle;
import battlecode.common.*;

/*
    A class that handles movement
 */

public class MovementSolver {
    MapLocation previous;
    RobotController rc;

    public MovementSolver(RobotController rc) {
        this.rc = rc;
    }

    public Direction directionToGoal(MapLocation goal) throws GameActionException {
        return directionToGoal(rc.getLocation(), goal);
    }

    public Direction directionToGoal(MapLocation from, MapLocation goal) throws GameActionException {
        if (!rc.isReady()) Clock.yield(); // canMove considers cooldown time

        Direction dir = from.directionTo(goal);
        int changes = 0;
        // while obstacle ahead, keep rotating
        while (isObstacle(dir, from.add(dir))) {
            dir = dir.rotateLeft();
            changes++;
            // if blocked in every direction, stop rotating
            if (changes > 8) return Direction.CENTER;
        }
        previous = from;
        return dir;
    }

    boolean isObstacle(Direction dir, MapLocation to) throws GameActionException {
        //point is obstacle if there is a building, is not on map (checked by canMove)
        // if it is flooded, or is previous point
        return !rc.canMove(dir) || rc.senseFlooding(to) || to.equals(previous);
    }
}
