package currentBot;

import battlecode.common.*;

public class HitchHike {
    public MapLocation pos;
    public MapLocation goal;
    public int droneID = -1;
    public int roundNum;
    public int reqID;
    public boolean confirmed = false;

    public HitchHike(MapLocation pos, MapLocation goal, int reqID) {
        this.pos = pos;
        this.goal = goal;
        this.reqID = reqID;
    }

    public HitchHike(MapLocation pos, MapLocation goal, int reqID, int droneID) {
        this.pos = pos;
        this.goal = goal;
        this.reqID = reqID;
        this.droneID = droneID;
    }

    public String toString() {
        return this.pos.toString() + " -> " + this.goal.toString();
    }
}
