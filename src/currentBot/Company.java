package currentBot;

import battlecode.common.MapLocation;

public class Company {
    public MapLocation landscaperPos = null;
    public int minerID = -1;
    public int roundNum = -1;
    public boolean confirmed = false;

    public Company(MapLocation pos, int ID) {
        this.landscaperPos = pos;
        this.minerID = ID;
    }
}
