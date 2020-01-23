package whyPermutatorOld;

public class RingQueue<T> {
    // Thanks jerry
    // https://github.com/j-mao/battlecode-2019/blob/master/newstart/DankQueue.java

    public T[] buf;
    public int l;
    public int r;
    public int ln;

    public RingQueue() {
        ln = 10000;
        buf = (T[]) new Object[ln];
        l = 0;
        r = 0;
    }

    public RingQueue(int maxlen) {
        ln = maxlen + 5;
        buf = (T[]) new Object[ln];
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

    public boolean add(T e) {
        if ((r + 1) % ln == l) {
            return false;
        }
        buf[r] = e;
        r = (r + 1) % ln;
        return true;
    }

    public boolean addFront(T e) {
        int newl = l-1;
        if (newl == -1) newl = ln-1;
        if (newl == r) return false;
        buf[newl] = e;
        l = newl;
        return true;
    }

    public T peek() {
        if (l == r) {
            return null;
        }
        return buf[l];
    }

    public T poll() {
        if (l == r) {
            return null;
        }
        T v = buf[l];
        l = (l + 1) % ln;
        return v;
    }
}
