package currentBot;
import battlecode.common.*;
import currentBot.Controllers.Controller;
import currentBot.Controllers.DeliveryDroneControllerMk2;
import currentBot.Controllers.PlayerConstants;

import java.util.ArrayList;

/*
    A class that handles movement
 */

public class MovementSolver {
    final static int DISTANCE_FROM_EDGE = 1;
    final static int NET_GUN_RANGE = 15;

    MapLocation previous;
    MapLocation twoback;
    Direction previousDir = null;
    boolean rotateCW = true;

    RobotController rc;
    Controller controller;
    final int recency = 9;
    int index = 0;
    ArrayList<MapLocation> recent = new ArrayList<>(recency);

    Direction[] cardinal = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    Direction[] ordinal  = {Direction.NORTHEAST, Direction.SOUTHEAST, Direction.SOUTHWEST, Direction.NORTHWEST};

    Direction[] getClosestDirections(Direction d) {
        return new Direction[]{d, d.rotateLeft(), d.rotateRight(), d.rotateLeft().rotateLeft(),
                d.rotateRight().rotateRight(), d.opposite().rotateRight(), d.opposite().rotateLeft(),
                d.opposite()};
    }

    public MovementSolver(RobotController rc) {
        this.rc = rc;
    }

    public MovementSolver(RobotController rc, Controller controller) {
        this.rc = rc;
        this.controller = controller;
        for (int i=0;i<recency;++i) recent.add(new MapLocation(-1,-1));
    }

    public Direction directionToGoal(MapLocation goal, boolean giveFucks) throws GameActionException {
        if (!giveFucks) {
            MapLocation from = rc.getLocation();

            if (!rc.isReady()) Clock.yield(); // canMove considers cooldown time


            Direction dir = from.directionTo(goal);

            int changes = 0;
            boolean failed = false;

            // while obstacle ahead, keep rotating
            while (isObstacleDrone(dir, from.add(dir))) {
                if (!rc.onTheMap(rc.getLocation().add(dir))) {
                    rotateCW = !rotateCW; previous = null; failed = true;
                    changes = 0;
                }
                ++changes;
                dir = (rotateCW) ? dir.rotateRight() : dir.rotateLeft();
                // if blocked in every direction, stop rotating
                if (changes > 8) return from.directionTo(previous);
            }


            rc.setIndicatorLine(from, goal, 255, 255, 255);

            if (failed) {
                // rotateCW = !rotateCW;
                rc.setIndicatorDot(from, 255, 0, 0);
                rc.setIndicatorLine(from, goal, 255, 0, 0);
            }

            if (rc.getLocation().add(dir).equals(twoback)) {
                rotateCW = !rotateCW;
            }

            twoback = previous;
            previous = from;
            return dir;
        } else {
            // avoid net-guns
            return droneDirectionToGoal(rc.getLocation(), goal);
        }
    }

    public Direction directionToGoal(MapLocation goal) throws GameActionException {
        rc.setIndicatorLine(rc.getLocation(), goal, 255, 0, 0);
        return rc.getType() == RobotType.DELIVERY_DRONE ?
                droneDirectionToGoal(rc.getLocation(), goal) :
                directionToGoal(rc.getLocation(), goal);
    }

    public Direction directionFromPoint(MapLocation point) throws GameActionException {
        return rc.getType() == RobotType.DELIVERY_DRONE ?
                droneDirectionToGoal(point, rc.getLocation()) :
                directionToGoal(point, rc.getLocation());
    }

    public Direction directionGo(Direction direction) throws GameActionException {
        return rc.getType() == RobotType.DELIVERY_DRONE ?
                droneDirectionToGoal(rc.getLocation(), rc.getLocation().add(direction)) :
                directionToGoal(rc.getLocation(), rc.getLocation().add(direction));
    }

    // no revisits to given number of recent tiles
    public Direction directionToGoal(MapLocation from, MapLocation goal) throws GameActionException {
        rc.setIndicatorLine(from, goal, 0, 0, 255);
        if (rc.getType() == RobotType.DELIVERY_DRONE) return droneDirectionToGoal(from, goal);

        while (!rc.isReady()) Clock.yield(); // canMove considers cooldown time

        // while obstacle ahead, keep looking for new direction
        Direction dir = from.directionTo(goal);
        for (Direction d : getClosestDirections(dir)) {
            if (!isObstacle(d, from.add(d))) {
                recent.set(index, from); index = (index + 1)%recency;
                return d;
            }
        }
        // currently stuck
        recent.set(index, from); index = (index + 1)%recency;
        return Direction.CENTER;

    }

    public Direction droneDirectionToGoal(MapLocation goal) throws GameActionException {
        return droneDirectionToGoal(rc.getLocation(), goal);
    }

    public Direction droneDirectionToGoal(MapLocation from, MapLocation goal) throws GameActionException {
        if (!rc.isReady()) Clock.yield();

        Direction dir = from.directionTo(goal);
        int changes = 0;
        // while obstacle ahead, keep rotating
        while (isDroneObstacleAvoidGun(dir, from.add(dir), controller.enemies)) {
            dir = rc.getID() % 4 == 0 ? dir.rotateLeft() : dir.rotateRight();
            changes++;
            // if blocked in every direction, stop rotating
            if (changes > 8) {
                dir = Direction.CENTER;
                break;
            }
        }

        // move away from enemy HQ if within range of their net gun
        if (controller.enemyHQ != null && from.isWithinDistanceSquared(controller.enemyHQ, NET_GUN_RANGE)) {
            dir = controller.enemyHQ.directionTo(from);
        }

        if (rc.getLocation().add(dir).equals(twoback)) {
            ((DeliveryDroneControllerMk2) controller).currentState = DeliveryDroneControllerMk2.State.ATTACK;
            if (rc.isCurrentlyHoldingUnit()) {
                ((DeliveryDroneControllerMk2) controller).currentState = DeliveryDroneControllerMk2.State.STUCKKILL;
            }
        }

        twoback = previous;
        previous = from;
        return dir;
    }

    public int distance(MapLocation p1, MapLocation p2) {
        // Accounts for the fact that you can move diagonally
        return Math.max(Math.abs(p1.x - p2.x), Math.abs(p1.y - p2.y));
    }

    public void restart() {
    }

    boolean isObstacle(Direction dir, MapLocation to) throws GameActionException {
        //point is obstacle if there is a building, is not on map (checked by canMove)
        // if it is flooded, next to a drone or is previous point

        // pretty crap implementation to avoid drones since gets stuck if many drones around
//        boolean alreadyInDroneRange = false;
//        controller.scanRobots();
//        for (RobotInfo robotInfo : controller.enemies) {
//            if (robotInfo.getType() == RobotType.DELIVERY_DRONE &&
//                    rc.getLocation().isAdjacentTo(robotInfo.getLocation())) {
//                System.out.println("already adjacent to drone");
//                alreadyInDroneRange = true;
//            }
//        }
//        if (!alreadyInDroneRange) {
//            for (RobotInfo robotInfo : controller.enemies) {
//                if (robotInfo.getType() == RobotType.DELIVERY_DRONE &&
//                        to.isAdjacentTo(robotInfo.getLocation())) {
//                    return true;
//                }
//            }
//        }

        return !rc.canMove(dir) || rc.senseFlooding(to) || recent.contains(to);
//                ||
//                (controller.rc.getType() == RobotType.MINER &&
//                        controller.allyHQ != null &&
//                        to.isAdjacentTo(controller.allyHQ))

    }

    boolean isObstacleDrone(Direction dir, MapLocation to) throws GameActionException {
        return !rc.canMove(dir) || to.equals(previous);
    }

    public Direction droneMoveAvoidGun(MapLocation goal) throws GameActionException {
        return droneMoveAvoidGun(rc.getLocation(), goal);
    }

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

    boolean isDroneObstacleAvoidGun(Direction dir, MapLocation to, RobotInfo[] enemies) throws GameActionException {
        if (enemies == null) enemies = rc.senseNearbyRobots();
        for (RobotInfo enemy : enemies) {
            if (enemy.getTeam() != rc.getTeam().opponent()) continue;
            if ((enemy.getType() == RobotType.HQ || enemy.getType() == RobotType.NET_GUN) && to.isWithinDistanceSquared(enemy.getLocation(), NET_GUN_RANGE)) {
                controller.communicationHandler.sendEnemyHQLoc(enemy.getLocation());
                return true;
            }
        }

        if (controller.enemyHQ == null) {
            if (controller.allyHQ != null) {
                int x = controller.allyHQ.x;
                int y = controller.allyHQ.y;

                MapLocation loc;

                if (controller.ghostH) { // Horizontal symmetry
                    loc = new MapLocation(rc.getMapWidth()-x-1, y);
                    if (rc.canSenseLocation(loc)) {
                        if (rc.senseRobotAtLocation(loc) != null) {
                            if (rc.senseRobotAtLocation(loc).getType() != RobotType.HQ) {
                                ((DeliveryDroneControllerMk2) controller).ghostH = false;
                                controller.communicationHandler.sendFailHorizontal();
                                System.out.println("no ghosts here");
                            } else {
                                if (controller.enemyHQ == null) {
                                    controller.enemyHQ = loc;
                                    controller.communicationHandler.sendEnemyHQLoc(loc);
                                }
                            }
                        }
                    }
                    if (to.isWithinDistanceSquared(loc, NET_GUN_RANGE)) {
                        return true; // because uncertain if hq is here or not since cannot sense
                    }
                }

                if (controller.ghostV) { // Vertical symmetry
                    loc = new MapLocation(x, rc.getMapHeight()-y-1);
                    if (rc.canSenseLocation(loc)) {
                        if (rc.senseRobotAtLocation(loc) != null) {
                            if (rc.senseRobotAtLocation(loc).getType() != RobotType.HQ) {
                                ((DeliveryDroneControllerMk2) controller).ghostV = false;
                                controller.communicationHandler.sendFailVertical();
                                System.out.println("no ghosts here");
                            } else {
                                if (controller.enemyHQ == null) {
                                    controller.enemyHQ = loc;
                                    controller.communicationHandler.sendEnemyHQLoc(loc);
                                }
                            }
                        }
                    }
                    if (to.isWithinDistanceSquared(loc, NET_GUN_RANGE)) {
                        return true; // because uncertain if hq is here or not since cannot sense
                    }
                }

                if (controller.ghostR) { // Rotational symmetry
                    loc = new MapLocation(rc.getMapWidth()-x-1, rc.getMapHeight()-y-1);
                    if (rc.canSenseLocation(loc)) {
                        if (rc.senseRobotAtLocation(loc) != null) {
                            if (rc.senseRobotAtLocation(loc).getType() != RobotType.HQ) {
                                ((DeliveryDroneControllerMk2) controller).ghostR = false;
                                controller.communicationHandler.sendFailRotational();
                                System.out.println("no ghosts here");
                            } else {
                                if (controller.enemyHQ == null) {
                                    controller.enemyHQ = loc;
                                    controller.communicationHandler.sendEnemyHQLoc(loc);
                                }
                            }
                        }
                    }
                    if (to.isWithinDistanceSquared(loc, NET_GUN_RANGE)) {
                        return true; // because uncertain if hq is here or not since cannot sense
                    }
                }
            }
        }

        // FIXME : make more general by storing other net gun locations
        if (controller.enemyHQ != null) {
            if (to.isWithinDistanceSquared(controller.enemyHQ, NET_GUN_RANGE)) {
                return true;
            }
        }

        return !rc.canMove(dir);
    }

    public void windowsRoam() throws GameActionException {
        if (previousDir == null) {
            previousDir = controller.spawnBaseDirFrom;
        }

        Direction direction = previousDir;

        if (isCardinal(direction)) {
            direction = (rc.getID() & 1) == 0 ? direction.rotateLeft() : direction.rotateRight();
        }

        if (nearHEdge()) {
            switch (direction) {
                case NORTHEAST: case SOUTHWEST: direction = rotate90R(direction); break;
                case NORTHWEST: case SOUTHEAST: direction = rotate90L(direction); break;
            }
        }

        if (nearVEdge()) {
            switch (direction) {
                case NORTHEAST: case SOUTHWEST: direction = rotate90L(direction); break;
                case NORTHWEST: case SOUTHEAST: direction = rotate90R(direction); break;
            }
        }

        controller.tryMove(directionGo(direction));

        previousDir = direction;
    }

    public boolean onTheMap(MapLocation pos) {
        return (0 <= pos.x && pos.x < rc.getMapWidth() && 0 <= pos.y && pos.y < rc.getMapHeight());
    }

    public boolean nearEdge() {
        int x = rc.getLocation().x;
        int y = rc.getLocation().y;

        int HSize = this.rc.getMapHeight();
        int WSize = this.rc.getMapWidth();

        return (x <= DISTANCE_FROM_EDGE || x >= WSize - DISTANCE_FROM_EDGE || y <= DISTANCE_FROM_EDGE || y >= HSize - DISTANCE_FROM_EDGE);
    }

    public boolean nearHEdge() {
        return (rc.getLocation().y <= DISTANCE_FROM_EDGE || rc.getLocation().y >= rc.getMapHeight() - DISTANCE_FROM_EDGE);
    }

    public boolean nearVEdge() {
        return (rc.getLocation().x <= DISTANCE_FROM_EDGE || rc.getLocation().x >= rc.getMapWidth() - DISTANCE_FROM_EDGE);
    }

    public Direction rotate90L(Direction direction) {
        return direction.rotateLeft().rotateLeft();
    }

    public Direction rotate90R(Direction direction) {
        return direction.rotateRight().rotateRight();
    }

    public boolean isCardinal(Direction dir) {
        for (Direction direction : cardinal) {
            if (dir == direction) return true;
        }
        return false;
    }

    public boolean isOrdinal(Direction dir) {
        return !isCardinal(dir);
    }


}
