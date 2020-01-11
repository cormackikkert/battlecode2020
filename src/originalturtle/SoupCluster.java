package originalturtle;

import battlecode.common.*;

public class SoupCluster {
    /*
        Stores information regarding clusters of soup
     */

    public int x1, x2, y1, y2;
    public int width;
    public int height;
    public int size;
    public SoupCluster(int x1, int y1, int x2, int y2, int size) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        width = x2 - x1 + 1;
        height = y2 - y1 + 1;
        this.size = size;
    }

    public MapLocation closest(MapLocation pos) {
        // Returns closest position in cluster to pos
        int tx, ty;
        if (pos.y >= y2) ty = y2;
        else if (pos.y <= y1) ty = y1;
        else ty = pos.y;

        if (pos.x >= x2) tx = x2;
        else if (pos.x <= x1) tx = x1;
        else tx = pos.x;

        return new MapLocation(tx, ty);
    }
    public boolean inside(SoupCluster other) {
        return (other.x1 <= this.x1 && this.x2 <= other.x2 && other.y1 <= this.y1 && this.y2 <= other.y2);
    }
}
