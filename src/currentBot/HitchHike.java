package currentBot;

import battlecode.common.*;

public class HitchHike {
    public MapLocation pos;
    public MapLocation goal;
    public int droneID = -1;
    public int roundNum;
    public boolean confirmed = false;

    public HitchHike(MapLocation pos, MapLocation goal) {
        this.pos = pos;
        this.goal = goal;
    }

    public HitchHike(MapLocation pos, MapLocation goal, int droneID) {
        this.pos = pos;
        this.goal = goal;
        this.droneID = droneID;
    }
}
