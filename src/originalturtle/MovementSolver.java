package originalturtle;
import battlecode.common.*;

/*
    A class that handles movement
 */

public class MovementSolver {
    final static int DISTANCE_FROM_EDGE = 3;
    final static int NET_GUN_RANGE = 15;

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

    public void restart() {
        this.history.clear();
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


        RobotInfo[] enemies = rc.senseNearbyRobots();
//        System.out.println("sensing robots in range "+rc.getCurrentSensorRadiusSquared());

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
            if (enemy.getTeam() != rc.getTeam().opponent()) continue;
            if (
//                    enemy.getType() == RobotType.DELIVERY_DRONE && to.isWithinDistanceSquared(enemy.getLocation(), 2)|| TODO : figure out optimal way to deal with opposing drones
                    (enemy.getType() == RobotType.HQ || enemy.getType() == RobotType.NET_GUN) && to.isWithinDistanceSquared(enemy.getLocation(), NET_GUN_RANGE)) {
//                System.out.println("dangerous! within range "+to.distanceSquaredTo(enemy.getLocation()));
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
