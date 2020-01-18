package currentBot;

import battlecode.common.MapLocation;
import battlecode.common.RobotController;

public class SoupCluster {
    /*
        Stores information regarding clusters of soup
     */

    public int x1, x2, y1, y2;
    public int X, Y; // soup center
    public MapLocation center;
    public MapLocation middle;
    public int width;
    public int height;
    public int size;
    public int crudeSoup;
    public boolean containsWaterSoup;

    public SoupCluster(int x1, int y1, int x2, int y2, int size, int crudeSoup, boolean containsWaterSoup) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        this.X = (Math.abs(x2 - x1)) / 2;
        this.Y = (Math.abs(y2 - y1)) / 2;
        this.center = new MapLocation(X, Y);
        this.middle = new MapLocation((x1 + x2) / 2, (y1 + y2) / 2);
        width = x2 - x1 + 1;
        height = y2 - y1 + 1;
        this.size = size;
        this.crudeSoup = crudeSoup;
        this.containsWaterSoup = containsWaterSoup;
    }

    public MapLocation closest(MapLocation pos) {
        // Returns closest position in cluster to pos
        int tx, ty;
        if (pos.y <= y1) ty = y1;
        else if (y2 <= pos.y) ty = y2;
        else ty = pos.y;

        if (pos.x <= x1) tx = x1;
        else if (x2 <= pos.x) tx = x2;
        else tx = pos.x;

        return new MapLocation(tx, ty);
    }
    public boolean inside(SoupCluster other) {
        return (other.x1 <= this.x1 && this.x2 <= other.x2 && other.y1 <= this.y1 && this.y2 <= other.y2);
    }

    public boolean insideThis(MapLocation loc) {
        return (this.x1 <= loc.x && loc.x <= this.x2 && this.y1 <= loc.y && loc.y <= this.y2);
    }

    public void update(SoupCluster other) {
        // Updates values to other
        this.x1 = other.x1;
        this.y1 = other.y1;
        this.x2 = other.x2;
        this.y2 = other.y2;
        width = x2 - x1 + 1;
        height = y2 - y1 + 1;
        this.size = other.size;
    }

    public boolean contains(MapLocation pos) {
        return x1 <= pos.x && pos.x <= x2 && y1 <= pos.y && pos.y <= y2;
    }
    public void draw(RobotController rc) {
        rc.setIndicatorLine(new MapLocation(x1, y1), new MapLocation(x1, y2), 255, 255, 0);
        rc.setIndicatorLine(new MapLocation(x2, y1), new MapLocation(x2, y2), 255, 255, 0);
        rc.setIndicatorLine(new MapLocation(x1, y1), new MapLocation(x2, y1), 255, 255, 0);
        rc.setIndicatorLine(new MapLocation(x1, y2), new MapLocation(x2, y2), 255, 255, 0);
    }

    public String toStringPos() {
        return String.format("[(%d, %d), (%d, %d)]", x1,y1,x2,y2);
    }

    public static final int CLUSTER_NEAR = 3;
    public boolean nearCluster(MapLocation location) {
        int x = location.x, y = location.y;
        return Math.abs(x - x1) <= CLUSTER_NEAR || Math.abs(x - x2) <= CLUSTER_NEAR ||Math.abs(y - y1) <= CLUSTER_NEAR ||Math.abs(y - y2) <= CLUSTER_NEAR;
    }
}
