package whyPermutator;

import battlecode.common.MapLocation;

public class RingQueueMapLocation {
    // Java handles array of generics weirdly so I just remade this class

    public MapLocation[] buf;
    public int l;
    public int r;
    public int ln;

    public RingQueueMapLocation() {
        ln = 10000;
        buf = new MapLocation[ln];
        l = 0;
        r = 0;
    }

    public RingQueueMapLocation(int maxlen) {
        ln = maxlen + 5;
        buf = new MapLocation[ln];
        l = 0;
        r = 0;
    }

    public boolean isEmpty() {
        return l == r;
    }

    public void clear() {
        l = r;
    }

    public int size() {
        return (r - l + ln) % ln;
    }

    public boolean add(MapLocation e) {
        if ((r + 1) % ln == l) {
            return false;
        }
        buf[r] = e;
        r = (r + 1) % ln;
        return true;
    }

    public boolean addFront(MapLocation e) {
        int newl = l-1;
        if (newl == -1) newl = ln-1;
        if (newl == r) return false;
        buf[newl] = e;
        l = newl;
        return true;
    }

    public MapLocation peek() {
        if (l == r) {
            return null;
        }
        return buf[l];
    }

    public MapLocation poll() {
        if (l == r) {
            return null;
        }
        MapLocation v = buf[l];
        l = (l + 1) % ln;
        return v;
    }
}
