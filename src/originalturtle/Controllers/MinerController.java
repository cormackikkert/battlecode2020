package originalturtle.Controllers;

import battlecode.common.*;

import java.util.LinkedList;

public class MinerController extends Controller {
    LinkedList<MapLocation> soupSquares = new LinkedList<>();
    public enum State {
        SEARCH,        // Explores randomly looking for soup
        SEARCHURGENT,  // Goes to area where it knows soup already is
        MINE,          // Mines soup in range
        DEPOSIT
    }

    // Mine variables
    MapLocation curSoupSource; // current source used in MINE state

    final int BIAS_TYPES = 16;
    int[] BIAS_DX = {0,1,2,3,4,3,2,1,0,-1,-2,-3,-4,-3,-2,-1};
    int[] BIAS_DY = {4,3,2,1,0,-1,-2,-3,-4,-3,-2,-1,0,1,2,3};

    int bias; // Which bias the robot has
    MapLocation BIAS_TARGET; // which square the robot is targeting
    MapLocation searchTarget;

    State currentState = State.SEARCH;
    int velx = 0;
    int vely = 0;

    MapLocation home;
    public MinerController(RobotController rc) {
        this.rc = rc;
        bias = (int) (Math.random() * BIAS_TYPES);

        for (RobotInfo robotInfo : rc.senseNearbyRobots()) {
            if (robotInfo.getType() == RobotType.HQ) home = robotInfo.location;
        }
    }

    public void run() throws GameActionException {
        switch (currentState) {
            case SEARCH: execSearch();             break;
            case MINE: execMine();                 break;
            case DEPOSIT: execDeposit();           break;
            case SEARCHURGENT: execSearchUrgent(); break;
        }
        /*
        tryBlockchain();
        tryMove(randomDirection());
        if (tryMove(randomDirection()))
            System.out.println("I moved!");
        // tryBuild(randomSpawnedByMiner(), randomDirection());
        for (Direction dir : directions)
            tryBuild(RobotType.FULFILLMENT_CENTER, dir);
        for (Direction dir : directions)
            if (tryRefine(dir))
                System.out.println("I refined soup! " + rc.getTeamSoup());
        for (Direction dir : directions)
            if (tryMine(dir))
                System.out.println("I mined soup! " + rc.getSoupCarrying());
        */
    }

    public void execSearch() throws GameActionException {
        for (Direction dir : directions)
            if (tryMine(dir)) {
                System.out.println("WOOHOO: ");
                MapLocation newSource = rc.getLocation().add(dir);
                soupSquares.add(newSource);
                curSoupSource = newSource;
                currentState = State.MINE;
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
        if (getDistanceSquared(rc.getLocation(), home) <= 1) {
            if (rc.isReady()) {
                rc.depositSoup(moveGreedy(rc.getLocation(), home), rc.getSoupCarrying());
                currentState = State.SEARCHURGENT;
            }
        } else {
            tryMove(moveGreedy(rc.getLocation(), home));
        }
    }

    public void execSearchUrgent() throws GameActionException {
        MapLocation nearestSoupSquare = soupSquares.get(0);
        int nearestSoupDist = getDistanceSquared(rc.getLocation(), soupSquares.get(0));

        for (MapLocation soupSquare : soupSquares) {
            if (getDistanceSquared(rc.getLocation(), soupSquare) < nearestSoupDist) {
                nearestSoupSquare = soupSquare;
                nearestSoupDist = getDistanceSquared(rc.getLocation(), soupSquare);
            }

        }

        while (getDistanceSquared(rc.getLocation(), nearestSoupSquare) > 1) {
            tryMove(moveGreedy(rc.getLocation(), nearestSoupSquare));
        }

        currentState = State.MINE;
    }

    int reduce(int val, int decay) {
        // Reduces magnitude of val by decay
        if (val > 0) return Math.max(0, val - decay);
        if (val < 0) return Math.min(0, val + decay);
        return val;
    }
}
