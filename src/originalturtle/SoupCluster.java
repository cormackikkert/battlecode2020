package originalturtle;

import battlecode.common.*;

public class SoupCluster {
    /*
        Stores information regarding clusters of soup
     */

    public int x1, x2, y1, y2;
    public int size;

    public SoupCluster(int x1, int y1, int x2, int y2, int size) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        this.size = size;
    }

    public boolean inside(SoupCluster other) {
        return (other.x1 <= this.x1 && this.x2 <= other.x2 && other.y1 <= this.y1 && this.y2 <= other.y2);
    }
}
