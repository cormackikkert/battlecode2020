package lightningTournamentSubmission;

// ty again https://github.com/j-mao/battlecode-2019/blob/master/newstart/SimpleRandom.java
public class SimpleRandom {

    private int state;

    public SimpleRandom() {
        state = 0xdeadc0de;
    }

    public SimpleRandom(int seed) {
        state = seed;
    }

    int advance(int value) {
        value ^= (value << 13);
        value ^= (value >> 17);
        value ^= (value << 5);
        return value;
    }

    public int nextInt() {
        state = advance(state);
        return Math.abs(state);
    }
}