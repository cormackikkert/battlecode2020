package whyPermutatorOld;

import battlecode.common.*;

public class MapBlock {
    // TODO: incorporate rising sea levels
    public MapLocation pos;
    public int soupCount;
    public int enemyCount;
    public boolean isReachable; // (for miners)

    public MapBlock(MapLocation pos, int soupCount, int enemyCount, boolean isReachable) {
        this.pos = pos;
        this.soupCount = soupCount;
        this.enemyCount = enemyCount;
        this.isReachable = isReachable;
    }
}
