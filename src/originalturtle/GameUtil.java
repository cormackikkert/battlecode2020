package originalturtle;

import battlecode.common.*;

public class GameUtil {
    public static int getDistance(MapLocation A, MapLocation B) { // get distance squared as in spec
        return (A.x - B.x) * (A.x - B.x) + (A.y - B.y) * (A.y - B.y);
    }

    public static boolean isAdjacent(RobotController A, RobotController B) {
        return getDistance(A.getLocation(), B.getLocation()) == 1;
    }

    public static boolean isAdjacent(RobotInfo A, RobotController B) {
        return getDistance(A.getLocation(), B.getLocation()) == 1;
    }
}
