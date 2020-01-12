package originalturtle;
import battlecode.common.*;

/*
    A class that handles movement
 */

public class MovementSolver {
    MapLocation previous;

    RobotController rc;
    boolean rotateCW = true;

    public RingQueueMapLocation history;

    public MovementSolver(RobotController rc) {
        this.rc = rc;
        history = new RingQueueMapLocation(this.rc.getMapHeight() * this.rc.getMapWidth());
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
            dir = (rotateCW) ? dir.rotateRight() : dir.rotateLeft();
            changes++;
            // if blocked in every direction, stop rotating
            if (changes > 8) return Direction.CENTER;
        }



        boolean failed = false;
        if (history.size() > 3) {
            for (int i = history.l; i != ((history.r - 3 + history.ln) % history.ln); i = (i + 1) % history.ln) {
                if (history.buf[i].equals(from.add(dir))) failed = true;
            }
        }
        if (failed) {
            history.clear();
            rotateCW = !rotateCW;
            rc.setIndicatorDot(from, 255, 0, 0);
            rc.setIndicatorLine(from, goal, 255, 0, 0);
        }

        history.add(from.add(dir));
        previous = from;
        return dir;
    }

    boolean isObstacle(Direction dir, MapLocation to) throws GameActionException {
        //point is obstacle if there is a building, is not on map (checked by canMove)
        // if it is flooded, or is previous point
        return !rc.canMove(dir) || rc.senseFlooding(to) || to.equals(previous);
    }
}
