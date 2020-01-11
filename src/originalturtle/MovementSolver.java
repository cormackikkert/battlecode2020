package originalturtle;
import battlecode.common.*;

/*
    A class that handles movement
 */

public class MovementSolver {
    final static int DISTANCE_FROM_EDGE = 3;

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

    // TODO modify for drones
    public Direction droneMoveAvoidGun(MapLocation goal) throws GameActionException {
        return droneMoveAvoidGun(rc.getLocation(), goal);
    }

    public static final int SENSOR_RADIUS = 24;
    public Direction droneMoveAvoidGun(MapLocation from, MapLocation goal) throws GameActionException {
        if (!rc.isReady()) Clock.yield(); // canMove considers cooldown time


        RobotInfo[] enemies = rc.senseNearbyRobots(SENSOR_RADIUS, rc.getTeam().opponent());

        Direction dir = from.directionTo(goal);
        int changes = 0;
        // while obstacle ahead, keep rotating
        while (isDroneObstacleAvoidGun(dir, from.add(dir), enemies)) {
            dir = dir.rotateLeft();
            changes++;
            // if blocked in every direction, stop rotating
            if (changes > 8) return Direction.CENTER;
        }
        previous = from;
        return dir;
    }

    /*
        Drones are weak to
            other drones (range 2 since moving next to them leaves chance to being captured)
            net guns (range 15 as in spec)
    */
    boolean isDroneObstacleAvoidGun(Direction dir, MapLocation to, RobotInfo[] enemies) throws GameActionException {
        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == RobotType.DELIVERY_DRONE && to.isWithinDistanceSquared(enemy.getLocation(), 2) ||
                    enemy.getType() == RobotType.HQ || enemy.getType() == RobotType.NET_GUN && to.isWithinDistanceSquared(enemy.getLocation(), 24)) {
                System.out.println("dangerous!");
                return true;
            }
        }

        return !rc.canMove(dir);
    }

    public boolean nearEdge() {
        int x = rc.getLocation().x;
        int y = rc.getLocation().y;

        int HSize = this.rc.getMapHeight();
        int WSize = this.rc.getMapWidth();

        return (x <= DISTANCE_FROM_EDGE || x >= WSize - DISTANCE_FROM_EDGE || y <= DISTANCE_FROM_EDGE || y >= HSize - DISTANCE_FROM_EDGE);
    }
}
