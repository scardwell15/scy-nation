package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;
import java.awt.Color;
import java.util.List;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_engineJumpstartStats extends BaseShipSystemScript {

  private final String BUFF_ID = "SCY_engineJumpstart";
  private boolean runOnce = false;
  private float repairMult = 1;

  @Override
  public void apply(
      MutableShipStatsAPI stats, String id, ShipSystemStatsScript.State state, float effectLevel) {
    ShipAPI ship = (ShipAPI) stats.getEntity();
    if (!runOnce) {
      runOnce = true;
      repairMult = Math.max(0, ship.getCurrentCR() - 0.5f);

      List<ShipEngineControllerAPI.ShipEngineAPI> engines =
          ship.getEngineController().getShipEngines();
      for (ShipEngineControllerAPI.ShipEngineAPI e : engines) {
        for (Integer i = 0; i < 10; i++) {
          Vector2f vel =
              MathUtils.getRandomPointInCone(
                  new Vector2f(), 50, ship.getFacing() + 160, ship.getFacing() + 200);
          vel = new Vector2f(vel.x + ship.getVelocity().x, vel.y + ship.getVelocity().y);
          float grey = (float) Math.random() * 0.2f + 0.1f;
          Global.getCombatEngine()
              .addSmokeParticle(
                  MathUtils.getRandomPointInCircle(e.getLocation(), 10),
                  vel,
                  20 + 5 * (float) Math.random(),
                  0.1f + 0.2f * (float) Math.random(),
                  0.5f + 1 * (float) Math.random(),
                  new Color(grey, grey, grey));
        }
      }
    }
    stats.getCombatEngineRepairTimeMult().modifyMult(BUFF_ID, repairMult);
  }

  @Override
  public void unapply(MutableShipStatsAPI stats, String id) {
    stats.getCombatWeaponRepairTimeMult().unmodify(BUFF_ID);
  }

  @Override
  public ShipSystemStatsScript.StatusData getStatusData(
      int index, ShipSystemStatsScript.State state, float effectLevel) {
    return null;
  }
}
