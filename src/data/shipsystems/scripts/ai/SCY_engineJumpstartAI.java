package data.shipsystems.scripts.ai;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.util.IntervalUtil;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import java.util.List;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_engineJumpstartAI implements ShipSystemAIScript {
  private CombatEngineAPI engine;
  private ShipAPI ship;
  private ShipSystemAPI system;
  private final IntervalUtil timer = new IntervalUtil(1, 2);
  private boolean flameout;

  @Override
  public void init(
      ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
    this.ship = ship;
    this.system = system;
    this.engine = engine;
    timer.randomize();
  }

  @Override
  public void advance(
      float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {

    if (engine.isPaused()) {
      return;
    }

    timer.advance(amount);
    if (timer.intervalElapsed()) {

      List<ShipEngineAPI> engines = ship.getEngineController().getShipEngines();
      if (!engines.isEmpty()) {
        Integer fraction = 0;
        for (ShipEngineAPI e : engines) {
          if (e.isSystemActivated() || e.isDisabled()) {
            fraction++;
          }
        }
        flameout = fraction == engines.size();
      }

      if (flameout && !system.isActive() && AIUtils.canUseSystemThisFrame(ship)) {
        ship.useSystem();
        if (MagicRender.screenCheck(0.1f, ship.getLocation())) {
          for (ShipEngineAPI e : engines) {
            for (Integer i = 0; i < 10; i++) {
              Vector2f vel =
                  MathUtils.getRandomPointInCone(
                      new Vector2f(), 100, ship.getFacing() + 160, ship.getFacing() + 200);
              vel = new Vector2f(vel.x + ship.getVelocity().x, vel.y + ship.getVelocity().y);
              float grey = (float) Math.random() * 0.2f + 0.1f;
              engine.addSmokeParticle(
                  MathUtils.getRandomPointInCircle(e.getLocation(), 10),
                  vel,
                  10 + 5 * (float) Math.random(),
                  0.1f + 0.2f * (float) Math.random(),
                  0.5f + 1 * (float) Math.random(),
                  new Color(grey, grey, grey));
            }
          }
        }
        flameout = false;
      }
    }
  }
}
