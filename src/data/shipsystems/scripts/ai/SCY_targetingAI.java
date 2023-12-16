package data.shipsystems.scripts.ai;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.util.IntervalUtil;
import data.shipsystems.scripts.SCY_targetingStats;
import data.shipsystems.scripts.SCY_targetingStats.targetingMode;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_targetingAI implements ShipSystemAIScript {
  private CombatEngineAPI engine;
  private ShipAPI ship;
  private ShipwideAIFlags flags;
  private boolean runOnce = false;
  //    private boolean ignoreArti=false;
  private final IntervalUtil timer = new IntervalUtil(0.25f, 0.5f);

  @Override
  public void init(
      ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
    this.ship = ship;
    this.engine = engine;
    this.flags = flags;
    timer.randomize();
  }

  @Override
  public void advance(
      float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {

    if (engine.isPaused() || ship.getShipAI() == null) {
      return;
    }

    if (!runOnce) {
      runOnce = true;
      int pd = 0;
      int missile = 0;
      for (WeaponAPI w : ship.getAllWeapons()) {
        if (w.getSlot().getId().startsWith("MAW")) {
          if (w.getType() == WeaponType.MISSILE) {
            missile++;
          }
          if (w.hasAIHint(WeaponAPI.AIHints.PD)) {
            pd++;
          }
        }
      }
    }

    timer.advance(amount);

    if (timer.intervalElapsed()) {

      boolean flux = ship.getFluxLevel() > 0.75;
      int missiles = AIUtils.getNearbyEnemyMissiles(ship, 750).size();
      boolean noShip = AIUtils.getNearbyEnemies(ship, 800).isEmpty();

      // fluxed out ships get defensive
      // if there are a LOT of missiles around or ship is at high flux, toggle defense
      if ((flux && flags.hasFlag(ShipwideAIFlags.AIFlags.BACKING_OFF))
          || (noShip && missiles > 3)) {
        if (SCY_targetingStats.getMode(ship) != targetingMode.PD
            && AIUtils.canUseSystemThisFrame(ship)) {
          ship.useSystem();
          //                    SCY_targetingStats.setMode(ship, targetingMode.PD);
        }
      } else
      // if there are almost no missiles in short range and few fighters, toggle artillery
      if (
      //                    !ignoreArti &&
      missiles <= 1 && noShip) {
        if (SCY_targetingStats.getMode(ship) != targetingMode.HEAVY
            && AIUtils.canUseSystemThisFrame(ship)) {
          ship.useSystem();
          //                    SCY_targetingStats.setMode(ship, targetingMode.HEAVY);
        }
      } else
      // else, toggle balanced
      {
        if (SCY_targetingStats.getMode(ship) != targetingMode.BALANCED
            && AIUtils.canUseSystemThisFrame(ship)) {
          ship.useSystem();
          //                    SCY_targetingStats.setMode(ship, targetingMode.BALANCED);
        }
      }
    }
  }
}
