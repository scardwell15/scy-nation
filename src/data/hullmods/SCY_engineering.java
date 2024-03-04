package data.hullmods;

import static data.scripts.util.SCY_settingsData.engineering_noncompatible;
import static data.scripts.util.SCY_txt.txt;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonalityAPI;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.util.IntervalUtil;
import org.magiclib.util.MagicIncompatibleHullmods;

import java.util.*;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.AIUtils;


public class SCY_engineering extends BaseHullMod {

  private final Map<HullSize, Float> VENTING_BONUS = new HashMap();

  {
    VENTING_BONUS.put(HullSize.FIGHTER, 2.5f);
    VENTING_BONUS.put(HullSize.FRIGATE, 2.5f);
    VENTING_BONUS.put(HullSize.DESTROYER, 2.25f);
    VENTING_BONUS.put(HullSize.CRUISER, 2f);
    VENTING_BONUS.put(HullSize.CAPITAL_SHIP, 1.75f);
  }

  private final float SENSOT_STILL = -25, SENSOR_MOVE = 25;
  private boolean runOnce = false;
  private float maxRange = 0;
  private final IntervalUtil timer = new IntervalUtil(0.5f, 1.5f);
  private String ID;
  private float VENT_MULT = 3.5f;
  private float VENT_PERCENT_PER_CAP = 1f;
  private float CAP_MULT = 2f;


  @Override
  public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
    stats.getVentRateMult().modifyMult(id, VENT_MULT);
    stats.getEngineDamageTakenMult().modifyMult(id, 0.75f);
    stats.getCombatEngineRepairTimeMult().modifyMult(id, 0.75f);
    ID = id;
  }

  @Override
  public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
    for (String tmp : engineering_noncompatible) {
      if (ship.getVariant().getHullMods().contains(tmp)) {
        MagicIncompatibleHullmods.removeHullmodWithWarning(ship.getVariant(), tmp, "SCY_engineering");
      }
    }
    ship.getMutableStats().getFluxCapacity().modifyMult(id, CAP_MULT);
    ship.getMutableStats().getVentRateMult().modifyPercent(id, ship.getVariant().getNumFluxCapacitors()*VENT_PERCENT_PER_CAP);
  }

  @Override
  public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
    if (index == 0) return "25" + txt("%");
    if (index == 1) return CAP_MULT + "x";
    if (index == 2) return VENT_MULT + "x";
    if (index == 3) return "+" + VENT_PERCENT_PER_CAP + txt("%");

    return null;
  }

  @Override
  public void advanceInCampaign(FleetMemberAPI member, float amount) {
    if (member.getFleetData() != null
        && member.getFleetData().getFleet() != null
        && !member.isMothballed()
        && !member.getFleetCommander().isPlayer()) {
      if (member.getFleetData().getFleet().getCurrBurnLevel() < 3) {
        //                member.getStats().getSensorProfile().modifyPercent(ID, -SENSOR_OFFSET);
        member.getStats().getSensorProfile().modifyPercent(ID, SENSOT_STILL);
      } else {
        //                member.getStats().getSensorProfile().modifyPercent(ID, +SENSOR_OFFSET);
        member.getStats().getSensorProfile().modifyPercent(ID, SENSOR_MOVE);
      }
    }
  }

  // MORE VENTING AI

  @Override
  public void advanceInCombat(ShipAPI ship, float amount) {

    if (!runOnce) {
      runOnce = true;
      List<WeaponAPI> loadout = ship.getAllWeapons();
      if (loadout != null) {
        for (WeaponAPI w : loadout) {
          if (w.getType() != WeaponAPI.WeaponType.MISSILE) {
            if (w.getRange() > maxRange) {
              maxRange = w.getRange();
            }
          }
        }
      }
      timer.randomize();

    }

    if (Global.getCombatEngine().isPaused() || ship.getShipAI() == null) {
      return;
    }
    timer.advance(amount);
    if (timer.intervalElapsed()) {
      if (ship.getFluxTracker().isOverloadedOrVenting()) {
        return;
      }
      MissileAPI closest = AIUtils.getNearestEnemyMissile(ship);
      if (closest != null && MathUtils.isWithinRange(ship, closest, 500)) {
        return;
      }

      if (ship.getFluxTracker().getFluxLevel() < 0.5
          && AIUtils.getNearbyEnemies(ship, maxRange) != null) {
        return;
      }

      // venting need

      float ventingNeed;
      switch (ship.getHullSize()) {
        case CAPITAL_SHIP:
          ventingNeed = 2 * (float) Math.pow(ship.getFluxTracker().getFluxLevel(), 5f);
          break;
        case CRUISER:
          ventingNeed = 1.5f * (float) Math.pow(ship.getFluxTracker().getFluxLevel(), 4f);
          break;
        case DESTROYER:
          ventingNeed = (float) Math.pow(ship.getFluxTracker().getFluxLevel(), 3f);
          break;
        default:
          ventingNeed = (float) Math.pow(ship.getFluxTracker().getFluxLevel(), 2f);
          break;
      }

      float hullFactor;
      switch (ship.getHullSize()) {
        case CAPITAL_SHIP:
          hullFactor = (float) Math.pow(ship.getHullLevel(), 0.4f);
          break;
        case CRUISER:
          hullFactor = (float) Math.pow(ship.getHullLevel(), 0.6f);
          break;
        case DESTROYER:
          hullFactor = ship.getHullLevel();
          break;
        default:
          hullFactor = (float) Math.pow(ship.getHullLevel(), 2f);
          break;
      }

      // situational danger

      float dangerFactor = 0;

      List<ShipAPI> nearbyEnemies = AIUtils.getNearbyEnemies(ship, 2000f);
      for (ShipAPI enemy : nearbyEnemies) {
        // reset often with timid or cautious personalities
        FleetSide side = FleetSide.PLAYER;
        if (ship.getOriginalOwner() > 0) {
          side = FleetSide.ENEMY;
        }
        if (Global.getCombatEngine().getFleetManager(side).getDeployedFleetMember(ship) != null) {
          PersonalityAPI personality =
              (Global.getCombatEngine().getFleetManager(side).getDeployedFleetMember(ship))
                  .getMember()
                  .getCaptain()
                  .getPersonalityAPI();
          if (personality.getId().equals("timid") || personality.getId().equals("cautious")) {
            if (enemy.getFluxTracker().isOverloaded()
                && enemy.getFluxTracker().getOverloadTimeRemaining()
                    > ship.getFluxTracker().getTimeToVent()) {
              continue;
            }
            if (enemy.getFluxTracker().isVenting()
                && enemy.getFluxTracker().getTimeToVent() > ship.getFluxTracker().getTimeToVent()) {
              continue;
            }
          }
        }

        switch (enemy.getHullSize()) {
          case CAPITAL_SHIP:
            dangerFactor +=
                Math.max(
                    0,
                    3f
                        - (MathUtils.getDistanceSquared(enemy.getLocation(), ship.getLocation())
                            / 1000000));
            break;
          case CRUISER:
            dangerFactor +=
                Math.max(
                    0,
                    2.25f
                        - (MathUtils.getDistanceSquared(enemy.getLocation(), ship.getLocation())
                            / 1000000));
            break;
          case DESTROYER:
            dangerFactor +=
                Math.max(
                    0,
                    1.5f
                        - (MathUtils.getDistanceSquared(enemy.getLocation(), ship.getLocation())
                            / 1000000));
            break;
          case FRIGATE:
            dangerFactor +=
                Math.max(
                    0,
                    1f
                        - (MathUtils.getDistanceSquared(enemy.getLocation(), ship.getLocation())
                            / 1000000));
            break;
          default:
            dangerFactor +=
                Math.max(
                    0,
                    0.5f
                        - (MathUtils.getDistanceSquared(enemy.getLocation(), ship.getLocation())
                            / 640000));
            break;
        }
      }

      float decisionLevel = (ventingNeed * hullFactor + 1) / (dangerFactor + 1);

      if (decisionLevel >= 1.5f
          || (ship.getFluxTracker().getFluxLevel() > 0.1f && dangerFactor == 0)) {
        ship.giveCommand(ShipCommand.VENT_FLUX, null, 0);
      }
    }
  }
}
