package originalturtle;

import battlecode.common.*;

public class SoupCluster {
    /*
        Stores information regarding clusters of soup
     */

    public MapLocation pos;
    public int size;

    public SoupCluster(MapLocation pos, int size) {
        this.pos = pos; this.size = size;
    }
}
