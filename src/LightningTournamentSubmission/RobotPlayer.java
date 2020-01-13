package lightningTournamentSubmission;

import battlecode.common.*;
import originalturtle.Controllers.*;

public class RobotPlayer {
    private static Controller myController;

    public static void run(RobotController rc) throws GameActionException {
        initialize(rc);

        while (true) {
            try {
                myController.run();
                Clock.yield();
            } catch (Exception e) {
                System.out.println(rc.getType() + " Exception");
                e.printStackTrace();
            }
        }
    }

    private static void initialize(RobotController rc) {
        switch (rc.getType()) {
            case HQ:                 myController = new HQController(rc);                   break;
            case MINER:              myController = new MinerController(rc);                break;
            case REFINERY:           myController = new RefineryController(rc);             break;
            case VAPORATOR:          myController = new VaporatorController(rc);            break;
            case DESIGN_SCHOOL:      myController = new DesignSchoolController(rc);         break;
            case FULFILLMENT_CENTER: myController = new FulfillmentCenterController(rc);    break;
            case LANDSCAPER:         myController = new LandscaperController(rc);           break;
            case DELIVERY_DRONE:     myController = new DeliveryDroneController(rc);        break;
            case NET_GUN:            myController = new NetGunController(rc);               break;

        }
    }
}
