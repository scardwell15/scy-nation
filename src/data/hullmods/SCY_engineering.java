package data.hullmods;

import static data.scripts.util.SCY_settingsData.engineering_noncompatible;
import static data.scripts.util.SCY_txt.txt;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonalityAPI;
import com.fs.starfarer.api.combat.BaseHullMod;
// import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.util.IntervalUtil;
import org.magiclib.util.MagicIncompatibleHullmods;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.AIUtils;

// import com.fs.starfarer.api.util.Misc;

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

  @Override
  public void applyEffectsBeforeShipCreation(
      HullSize hullSize, MutableShipStatsAPI stats, String id) {
    stats.getVentRateMult().modifyFlat(id, (Float) VENTING_BONUS.get(hullSize));
    stats.getEngineDamageTakenMult().modifyMult(id, 0.75f);
    stats.getCombatEngineRepairTimeMult().modifyMult(id, 0.75f);
    ID = id;
  }

  @Override
  public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
    for (String tmp : engineering_noncompatible) {
      if (ship.getVariant().getHullMods().contains(tmp)) {
        MagicIncompatibleHullmods.removeHullmodWithWarning(
            ship.getVariant(), tmp, "SCY_engineering");
      }
    }
  }

  @Override
  public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
    if (index == 0) return "+1";
    if (index == 1) return "+50" + txt("%");
    if (index == 2) return "+200" + txt("%");
    if (index == 3) return "+100" + txt("%");
    if (index == 4) return (int) SENSOT_STILL + txt("%");

    if (index == 5) return "-80" + txt("%");
    if (index == 6) return "-50" + txt("%");
    if (index == 7) return "+" + (int) SENSOR_MOVE + txt("%");

    if (index == 8) return "+25" + txt("%");
    //        if (index == 9) return txt("hm_engineer");
    // incompatibility list
    String list = "\n";
    for (String id : engineering_noncompatible) {
      if (Global.getSettings().getHullModSpec(id) == null) continue;
      list += " - ";
      list = list + Global.getSettings().getHullModSpec(id).getDisplayName();
      list += "\n";
    }
    if (index == 9) return list;
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
