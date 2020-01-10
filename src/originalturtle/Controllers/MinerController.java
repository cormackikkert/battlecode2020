package originalturtle.Controllers;

import battlecode.common.*;

import java.util.HashSet;

public class MinerController extends Controller {
    HashSet<MapLocation> soupSquares = new HashSet<>();

    public enum State {
        SEARCH,        // Explores randomly looking for soup
        SEARCHURGENT,  // Goes to area where it knows soup already is
        MINE,          // Mines soup in range
        DEPOSIT,
        BUILDER
    }

    // Mine variables
    MapLocation curSoupSource; // current source used in MINE state

    final int BIAS_TYPES = 16;
    int[] BIAS_DX = {0,1,2,3,4,3,2,1,0,-1,-2,-3,-4,-3,-2,-1};
    int[] BIAS_DY = {4,3,2,1,0,-1,-2,-3,-4,-3,-2,-1,0,1,2,3};

    /**
     * Trying to build in one of 8 locations (T) around spawn point (S): (can be improved, used for testing currently)
     * T T T
     * T S T
     * T T T
     */
    final int BUILD_LOCS = 8;
    int[] BUILD_DX = {-1,0,1,-1,1,-1,0,1};
    int[] BUILD_DY = {1,1,1,0,0,-1,-1,-1};

    int bias; // Which bias the robot has
    MapLocation BIAS_TARGET; // which square the robot is targeting
    MapLocation searchTarget;

    State currentState = State.SEARCH;
    int velx = 0;
    int vely = 0;

    RobotType buildType = null;
    MapLocation buildLoc;

    public MinerController(RobotController rc) {
        this.rc = rc;
        int round = rc.getRoundNum();
        bias = (int) (Math.random() * BIAS_TYPES);

        for (RobotInfo robotInfo : rc.senseNearbyRobots()) {
            if (robotInfo.getType() == RobotType.HQ) allyHQ = robotInfo.location;
        }

        if (round % 3 == 0) { // FIXME: used for testing building buildings
            System.out.println("me BUILDER");
            currentState = State.BUILDER;
            buildType = RobotType.FULFILLMENT_CENTER;
            if (!findBuildLoc()) currentState = State.SEARCH;
        }
    }

    /** run function **/
    public void run() throws GameActionException {
        switch (currentState) {
            case SEARCH: execSearch();             break;
            case MINE: execMine();                 break;
            case DEPOSIT: execDeposit();           break;
            case SEARCHURGENT: execSearchUrgent(); break;
            case BUILDER: execBuilder();           break;
        }
    }

    boolean containsEnoughSoup(int crudeCount) {
        // Made into a function incase we make it more intelligent later
        // e.g look for bigger soup containment's and get to it before enemy
        return crudeCount > 0;
    }

    public void execSearch() throws GameActionException {

        // Check to see if you can detect any soup
        for (int dx = -6; dx <= 6; ++dx) {
            for (int dy = -6; dy <= 6; ++dy) {
                MapLocation sensePos = new MapLocation(rc.getLocation().x + dx, rc.getLocation().y + dy);
                if (!rc.canSenseLocation(sensePos)) continue;

                int crudeAmount = rc.senseSoup(sensePos);
                if (rc.canSenseLocation(sensePos) && containsEnoughSoup(crudeAmount)) {
                    System.out.println("WOOHOO: (found soup)");
                    soupSquares.add(sensePos);
                }
            }
        }

        if (soupSquares.size() > 0) {
            currentState = State.SEARCHURGENT;
            return;
        }

        /* Movement approach:
            keep a velocity vector (velx, vely) and move in this direction.
            modify this to point away from other robots and obstructions

            This seems fairly extendable (can also make velocity vector point to target)
            Might incorporate this as default movement thingy
         */

        // Find average vector pointing to all other units
        int avgx = 0;
        int avgy = 0;
        for (RobotInfo robot : rc.senseNearbyRobots()) {
            int dist = getDistanceSquared(robot.location, rc.getLocation());
            avgx += Math.max(5-dist, 0) * (robot.location.x - rc.getLocation().x);
            avgy += Math.max(5-dist, 0) * (robot.location.y - rc.getLocation().y);
        }

        // Move in opposite direction
        velx -= avgx;
        vely -= avgy;

        if (!tryMove(moveGreedy(new MapLocation(0, 0), new MapLocation(velx, vely)))) {
            // Got stuck, reset velocity
            bias = (int) (Math.random() * 16);
            velx = 0;
            vely = 0;
        }

        if (velx == 0 && vely == 0) {

            velx = BIAS_DX[bias];
            vely = BIAS_DY[bias];
        }



        // Dampening (velocity reduces by 1) deleted might add back later
    }

    public void execMine() throws GameActionException {
        if (getDistanceSquared(rc.getLocation(), curSoupSource) <= 1) {
            if (!tryMine(moveGreedy(rc.getLocation(), curSoupSource))) {
                currentState = State.DEPOSIT;
            }
        } else {
            if (soupSquares.size() > 0)
                currentState = State.SEARCHURGENT;
            else
                currentState = State.SEARCH;
        }
    }

    public void execDeposit() throws GameActionException {
        if (getDistanceSquared(rc.getLocation(), allyHQ) <= 1) {
            if (rc.isReady() && rc.getSoupCarrying() > 0) {
                rc.depositSoup(moveGreedy(rc.getLocation(), allyHQ), rc.getSoupCarrying());
                currentState = State.SEARCHURGENT;
            }
        } else {
            tryMove(moveGreedy(rc.getLocation(), allyHQ));
        }
    }

    public void execSearchUrgent() throws GameActionException {
        MapLocation nearestSoupSquare = null;
        int nearestSoupDist = 2*65*65;

        System.out.println("Deciding: ");
        for (MapLocation soupSquare : soupSquares) {
            System.out.println("Dist: " + getDistanceSquared(rc.getLocation(), soupSquare));
            if (getDistanceSquared(rc.getLocation(), soupSquare) < nearestSoupDist) {
                nearestSoupSquare = soupSquare;
                nearestSoupDist = getDistanceSquared(rc.getLocation(), soupSquare);
            }
        }

        curSoupSource = nearestSoupSquare;

        while (getDistanceSquared(rc.getLocation(), nearestSoupSquare) > 1) {
            tryMove(moveGreedy(rc.getLocation(), nearestSoupSquare));
        }

        currentState = State.MINE;
    }

    public void execBuilder() throws GameActionException {
        if (isAdjacentTo(buildLoc)) {
            System.out.println("trying to build");
            switch (buildType) {
                case REFINERY: tryMultiBuild(RobotType.REFINERY); break;
                case VAPORATOR: tryMultiBuild(RobotType.VAPORATOR); break;
                case DESIGN_SCHOOL: tryMultiBuild(RobotType.DESIGN_SCHOOL); break;
                case FULFILLMENT_CENTER: tryMultiBuild(RobotType.FULFILLMENT_CENTER); break;
                case NET_GUN: tryMultiBuild(RobotType.NET_GUN); break;
            }
        } else {
            tryMove(moveGreedy(rc.getLocation(), buildLoc));
        }
    }

    int reduce(int val, int decay) {
        // Reduces magnitude of val by decay
        if (val > 0) return Math.max(0, val - decay);
        if (val < 0) return Math.min(0, val + decay);
        return val;
    }

    boolean findBuildLoc() {
        int x = rc.getLocation().x;
        int y = rc.getLocation().y;
        for (int j = 5; j >= 3; j--) {
            for (int i = 0; i < BUILD_LOCS; i++) {
                MapLocation loc = new MapLocation(x + BUILD_DX[i] * j, y + BUILD_DY[i] * j);
                try {
                    if (!rc.senseFlooding(loc) && rc.senseRobotAtLocation(loc) == null) {
                        buildLoc = loc;
                        return true;
                    }
                } catch (GameActionException e) {
//                    System.out.println("Cannot sense or build here");
                }
            }
        }
        return false;
    }

    void tryMultiBuild(RobotType robotType) throws GameActionException {
        for (Direction dir : directions) {
            if (rc.canBuildRobot(robotType, dir)) {
                rc.buildRobot(robotType, dir);
//                System.out.println("built a "+robotType);
                currentState = State.SEARCH; // FIXME: switch to search for testing purposes, specifically to conserve soup
                break;
            }
        }
    }
}
