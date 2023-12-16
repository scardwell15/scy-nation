package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;

public class SCY_armorChild extends BaseHullMod {

  private final String id = "SCY_childModule";

  @Override
  public void advanceInCombat(ShipAPI ship, float amount) {

    if (!ship.isAlive()) return;
    ship.setDrone(true);

    if (Global.getCombatEngine().getTotalElapsedTime(false) <= 2.05
        && Global.getCombatEngine().getTotalElapsedTime(false) > 2) {
      if (ship.getParentStation() != null && ship.getParentStation().isAlive()) {
        SCY_modularArmor.applyHullmodModificationsToStats(ship.getMutableStats(), ship.getHullSpec(), ship.getParentStation().getVariant());
      }
    }
  }

  @Override
  public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
    return null;
  }
}
