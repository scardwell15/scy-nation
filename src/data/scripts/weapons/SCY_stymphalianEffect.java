// By Tartiflette
package data.scripts.weapons;

import static data.scripts.util.SCY_txt.txt;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import org.magiclib.util.MagicRender;
import org.magiclib.util.MagicUI;
import java.awt.Color;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lazywizard.lazylib.combat.entities.SimpleEntity;
import org.lwjgl.util.vector.Vector2f;

public class SCY_stymphalianEffect implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {

  private boolean runOnce = false,
      active = false,
      ready = false,
      teleporterArmed = true,
      ventBoost = false,
      shieldBoost = false;

  private ShipAPI SHIP;
  private WeaponAPI SPARKS;
  private SpriteAPI HEAT, CAPACITOR, RAILS;
  private ShipSystemAPI SYSTEM;
  private float SHIELD_ARC = 0, SPARKS_WIDTH;
  private final Color NO_COLOR = new Color(0, 0, 0, 0);
  private int reverse = 1;
  private float heat = 0, capacitor = 0, rails = 0, flip = 1, overcharge = 0f;
  private final IntervalUtil timer = new IntervalUtil(0.05f, 0.05f);

  @Override
  public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
    if (ready) {

      int extraShots = 1 + ((int) Math.round(9 * overcharge));
      SYSTEM.setAmmo(0);
      rails = 1;
      overcharge = 0;
      capacitor = 0;
      ready = false;

      for (int y = 0; y < extraShots; y++) {
        engine.spawnProjectile(
            SHIP,
            weapon,
            "SCY_stymphalianSuper",
            projectile.getLocation(),
            projectile.getFacing(),
            SHIP.getVelocity());
      }
      engine.spawnProjectile(
          SHIP,
          weapon,
          "SCY_stymphalianMain",
          projectile.getLocation(),
          projectile.getFacing(),
          SHIP.getVelocity());
      engine.removeEntity(projectile);

      // flash
      Vector2f speed = SHIP.getVelocity();
      float facing = SHIP.getFacing();
      Vector2f barrel = new Vector2f(150, 0);
      Vector2f tip = VectorUtils.rotate(barrel, facing, barrel);
      SimpleEntity aim =
          new SimpleEntity(
              new Vector2f(weapon.getLocation().x + tip.x, weapon.getLocation().y + tip.y));

      if (MagicRender.screenCheck(0.25f, SHIP.getLocation())) {
        engine.spawnEmpArc(
            SHIP,
            weapon.getLocation(),
            SHIP,
            aim,
            DamageType.KINETIC,
            0,
            0,
            1000,
            null,
            2,
            Color.orange,
            Color.white);

        engine.addHitParticle(
            weapon.getLocation(), (Vector2f) speed.scale(.5f), 200, 2, 1f, Color.ORANGE);
        engine.addHitParticle(
            weapon.getLocation(), (Vector2f) speed.scale(.5f), 100, 2, 0.2f, Color.white);
        for (int x = 0; x < 10; x++) {
          engine.addHitParticle(
              weapon.getLocation(),
              MathUtils.getPoint(
                  null,
                  MathUtils.getRandomNumberInRange(100, 500),
                  MathUtils.getRandomNumberInRange(facing - 20f, facing + 20f)),
              MathUtils.getRandomNumberInRange(3, 10),
              1f,
              MathUtils.getRandomNumberInRange(0.5f, 1f),
              Color.orange);
        }
      }
      CombatUtils.applyForce(SHIP, SHIP.getFacing() + 180, 100 * overcharge);
      Global.getSoundPlayer()
          .playSound(
              "SCY_spear_chargedFire",
              1.25f - overcharge / 2,
              0.5f + overcharge / 2,
              SHIP.getLocation(),
              SHIP.getVelocity());
    }
  }

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {

    if (!runOnce) {
      runOnce = true;
      SHIP = weapon.getShip();
      SYSTEM = SHIP.getSystem();
      if (SHIP.getShield() != null) {
        SHIELD_ARC = SHIP.getShield().getArc();
      }

      for (WeaponAPI w : SHIP.getAllWeapons()) {
        switch (w.getSlot().getId()) {
          case "Z_SYSTEM1":
            if (SHIP.getOriginalOwner() != -1 && SHIP.getOwner() != -1) {
              w.getAnimation().setFrame(1);
            }
            HEAT = w.getSprite();
            break;
          case "Z_SYSTEM2":
            SPARKS = w;
            break;
          case "Z_SYSTEM3":
            if (SHIP.getOriginalOwner() != -1 && SHIP.getOwner() != -1) {
              w.getAnimation().setFrame(1);
            }
            CAPACITOR = w.getSprite();
            break;
          case "Z_HEAT_MAIN":
            if (SHIP.getOriginalOwner() != -1 && SHIP.getOwner() != -1) {
              w.getAnimation().setFrame(1);
            }
            RAILS = w.getSprite();
            break;
        }
      }

      SPARKS.getAnimation().setFrame(1);
      SPARKS_WIDTH = SPARKS.getSprite().getWidth();
      SPARKS.getAnimation().setFrame(0);

      HEAT.setColor(NO_COLOR);
      CAPACITOR.setColor(NO_COLOR);
      RAILS.setColor(NO_COLOR);

      // spread the load
      timer.randomize();
      return;
    }

    // UI
    UIEffect(overcharge, ready);

    // timer tic
    timer.advance(amount);

    // system initial activation
    if (SYSTEM.isActive() && !active) {
      active = true;
      ready = true;
      overcharge = 1;
      capacitor = 1;
      heat = 1;
      timer.forceIntervalElapsed();
      teleporterArmed = false;
      SYSTEM.setAmmo(1);
    }

    if (ready) {

      // teleporter cooldown protection
      if (SYSTEM.getCooldownRemaining() == 0) {
        teleporterArmed = true;
      }

      // weapon boost
      //            if(weapon.getChargeLevel()==1){
      ////                weaponEffect(engine,weapon);
      //                extraShots=1+((int)Math.round(9*overcharge));
      //                SYSTEM.setAmmo(0);
      //                rails=1;
      //                overcharge=0;
      //                capacitor=0;
      //                ready=false;
      //            } else
      // shield boost
      if (SHIP.getShield().isOn()) {
        shieldBoost = true;
        SYSTEM.setAmmo(0);
        ready = false;
      } else
      // system reuse
      if (SYSTEM.isActive() && teleporterArmed) {
        overcharge = 0;
        ready = false;
      } else
      // venting boost
      if (SHIP.getFluxTracker().isVenting()) {
        ventBoost = true;
        SYSTEM.setAmmo(0);
        Global.getSoundPlayer()
            .playSound("SCY_enhancedVent", 1f, 1, SHIP.getLocation(), SHIP.getVelocity());
        ready = false;
      }

    } else if (weapon.getChargeLevel() == 1) {
      rails = Math.min(1, rails + 0.5f);
      RAILS.setColor(new Color(1, 1, 1, rails));
    }

    // OVERCHARGE IS ACTIVE

    // 20fps check
    if (timer.intervalElapsed()) {

      if (active) {
        // overcharge fading
        overcharge = Math.max(0, overcharge - ((float) 1 / 150));

        String ID = "SCY_experimentalTeleporter";
        if (overcharge > 0) {

          // shieldBoost
          if (shieldBoost) {
            SHIP.getShield().setArc(SHIELD_ARC + (270 * overcharge));
            SHIP.getMutableStats().getShieldUnfoldRateMult().modifyMult(ID, 10f * overcharge);
            SHIP.getMutableStats().getShieldDamageTakenMult().modifyMult(ID, 1 - (1 * overcharge));

            if (MagicRender.screenCheck(0.5f, SHIP.getLocation())) {
              engine.addHitParticle(
                  MathUtils.getPoint(
                      SHIP.getShield().getLocation(),
                      SHIP.getShield().getRadius(),
                      (int)
                          MathUtils.getRandomNumberInRange(
                              SHIP.getFacing() - (SHIP.getShield().getArc() / 2),
                              SHIP.getFacing() + (SHIP.getShield().getArc() / 2))),
                  SHIP.getVelocity(),
                  5 + (float) Math.random() * 5,
                  1f,
                  0.1f + (float) Math.random() * 0.2f,
                  new Color(125, 200, 250, 150));
            }
          } else

          // ventBoost
          if (ventBoost) {
            SHIP.getMutableStats().getVentRateMult().modifyMult(ID, 10f * overcharge);

            // overcharge quickly dissipates
            overcharge = Math.max(0, overcharge - (float) 1 / 60);

            // STEAM
            if (SHIP.getFluxTracker().isVenting()
                && MagicRender.screenCheck(0.1f, SHIP.getLocation())) {
              for (int x = 0; x < Math.round(10 * overcharge); x++) {
                engine.addSmokeParticle(
                    SHIP.getLocation(),
                    MathUtils.getRandomPointInCircle(null, 50),
                    MathUtils.getRandomNumberInRange(25f, 100f),
                    MathUtils.getRandomNumberInRange(0.1f, 0.2f),
                    MathUtils.getRandomNumberInRange(0.2f, 2f),
                    new Color(1, 1, 1, 0.1f));
              }
            }
          }

          // Overcharge Trail
          if (MagicRender.screenCheck(0.25f, SHIP.getLocation())) {
            engine.addHitParticle(
                SHIP.getLocation(),
                new Vector2f(SHIP.getVelocity().x * 0.5f, SHIP.getVelocity().y * 0.5f),
                SHIP.getCollisionRadius() * (0.5f + overcharge),
                overcharge / 10,
                MathUtils.getRandomNumberInRange(0.5f, 1f + overcharge),
                new Color(0.3f, 0.1f, 0.3f));
          }

          // Time dilation
          float dilation = (1 + overcharge * 2);
          SHIP.getMutableStats().getTimeMult().modifyMult(ID, dilation);
          SHIP.getMutableStats().getDeceleration().modifyMult(ID, dilation);
          SHIP.getMutableStats().getAcceleration().modifyMult(ID, dilation);

          if (SHIP == engine.getPlayerShip()) {
            engine.getTimeMult().modifyMult(ID, 1f / dilation);
          } else {
            engine.getTimeMult().unmodify(ID);
          }

        } else { // RESTORE EVERYTHING
          overcharge = 0;
          ready = false;
          SYSTEM.setAmmo(0);
          if (shieldBoost) {
            shieldBoost = false;
            SHIP.getShield().setArc(SHIELD_ARC);
            SHIP.getMutableStats().getShieldUnfoldRateMult().unmodify(ID);
            SHIP.getMutableStats().getShieldDamageTakenMult().unmodify(ID);
          }
          if (ventBoost) {
            ventBoost = false;
            SHIP.getMutableStats().getVentRateMult().unmodify(ID);
          }

          SHIP.getMutableStats().getTimeMult().unmodify(ID);
          SHIP.getMutableStats().getDeceleration().unmodify(ID);
          SHIP.getMutableStats().getAcceleration().unmodify(ID);
          engine.getTimeMult().unmodify(ID);
        }

        // visual effect
        heat = Math.max(0, heat - ((float) 1 / 100));
        capacitor = Math.max(0, capacitor - (float) 1 / 150);
        if (heat <= 0 && capacitor <= 0) {
          active = false;
        } else {
          visualEffect();
        }
      }

      // always check the main gun glow
      rails = Math.max(0, rails - ((float) 1 / 150));
      RAILS.setColor(new Color(1, 1, 1, rails));
    }

    // SOUND
    if (ready) {
      Global.getSoundPlayer()
          .playLoop(
              "system_emp_emitter_loop",
              SHIP,
              2f + overcharge,
              overcharge / 2,
              SHIP.getLocation(),
              SHIP.getVelocity());
      Global.getSoundPlayer()
          .playLoop(
              "SCY_deconstruction_loop",
              SHIP,
              0.2f + overcharge / 2,
              overcharge / 2,
              SHIP.getLocation(),
              SHIP.getVelocity());
    }
  }

  //////////////////////////////
  //                          //
  //       UI FEEDBACK        //
  //                          //
  //////////////////////////////

  private void UIEffect(Float charge, boolean overcharge) {
    if (overcharge) {
      MagicUI.drawInterfaceStatusBar(
          SHIP, charge, Color.RED, null, charge, txt("wpn_bird"), Math.round(charge * 100));
    } else {
      MagicUI.drawInterfaceStatusBar(SHIP, heat, null, null, 0, txt("wpn_bird"), 0);
    }
  }

  //////////////////////////////
  //                          //
  //      DECOS EFFECT        //
  //                          //
  //////////////////////////////

  private void visualEffect() {
    // capacitor
    float capacitorAlpha =
        Math.min(
            1,
            Math.max(
                0,
                (1 - 1 / (float) Math.pow(capacitor + 1, 2))
                    + capacitor * ((float) Math.random() / 2)));
    CAPACITOR.setColor(new Color(capacitorAlpha, capacitorAlpha, 1, capacitorAlpha));

    // heat
    HEAT.setColor(new Color(1, heat, heat, heat)); // linear regression

    // spakles
    int frame = SPARKS.getAnimation().getFrame();
    if (Math.random()
        > 1 - overcharge) { // chance to skip the animation that grows as the Overcharge dwindle
      if (frame == 0) { // random start
        frame = (int) Math.round(Math.random() * (SPARKS.getAnimation().getNumFrames() - 1));
        if (Math.random() > 0.5) { // random flip
          flip *= -1;
        }
        if (Math.random() > 0.5) { // random flip
          reverse *= -1;
        }
      } else {
        frame = frame + reverse; // or play the animation normaly
        if (frame == SPARKS.getAnimation().getNumFrames() || frame < 0) {
          frame = (int) Math.round(Math.random() * (SPARKS.getAnimation().getNumFrames() - 1));
        }
      }
    } else {
      frame = 0;
    }
    SPARKS.getAnimation().setFrame(frame);
    SPARKS.getSprite().setWidth(SPARKS_WIDTH * flip);
    SPARKS.getSprite().setCenterX(SPARKS.getSprite().getWidth() / 2);
  }
}
