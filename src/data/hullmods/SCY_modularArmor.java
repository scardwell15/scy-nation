package data.hullmods;

import static data.scripts.util.SCY_txt.txt;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Pair;
import com.fs.starfarer.combat.entities.DamagingExplosion;
import org.lazywizard.lazylib.CollisionUtils;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.DefenseUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicIncompatibleHullmods;

import java.util.*;

public class SCY_modularArmor extends BaseHullMod {

  private final String SCY_ARMOR_MOD = "SCY_ARMOR_MOD";
  private final float SPEED_BONUS = 0.25f;

  @Override
  public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
    if(!ship.hasListenerOfClass(SCY_engineering.ExplosionOcclusionRaycast.class)) ship.addListener(new SCY_engineering.ExplosionOcclusionRaycast());
  }

  @Override
  public void advanceInCombat(ShipAPI ship, float amount) {

    if (Global.getCombatEngine().isPaused() || ship == null || ship.getOriginalOwner() == -1) {
      return;
    }

    if (!ship.isAlive()) {
      removeStats(ship);
      return;
    }

    float modules = 0;
    float alive = 0;
    for (ShipAPI s : ship.getChildModulesCopy()) {
      modules++;
      if (s.isAlive()) {
        alive++;
      }
    }

    if (modules != 0) {
      // speed bonus applies linearly
      float speedRatio = 1 - (alive / modules);
      applyStats(speedRatio, ship);
    }
  }

  private void removeStats(ShipAPI ship) {
    ship.getMutableStats().getMaxSpeed().unmodify(SCY_ARMOR_MOD);
    ship.getMutableStats().getAcceleration().unmodify(SCY_ARMOR_MOD);
    ship.getMutableStats().getDeceleration().unmodify(SCY_ARMOR_MOD);
    ship.getMutableStats().getMaxTurnRate().unmodify(SCY_ARMOR_MOD);
    ship.getMutableStats().getTurnAcceleration().unmodify(SCY_ARMOR_MOD);
  }

  private void applyStats(float speedRatio, ShipAPI ship) {
    ship.getMutableStats()
        .getMaxSpeed()
        .modifyMult(SCY_ARMOR_MOD, (1 + (speedRatio * SPEED_BONUS)));
    ship.getMutableStats()
        .getAcceleration()
        .modifyMult(SCY_ARMOR_MOD, (1 + (speedRatio * SPEED_BONUS)));
    ship.getMutableStats()
        .getDeceleration()
        .modifyMult(SCY_ARMOR_MOD, (1 + (speedRatio * SPEED_BONUS)));
    ship.getMutableStats()
        .getMaxTurnRate()
        .modifyMult(SCY_ARMOR_MOD, (1 + (speedRatio * SPEED_BONUS)));
    ship.getMutableStats()
        .getTurnAcceleration()
        .modifyMult(SCY_ARMOR_MOD, (1 + (speedRatio * SPEED_BONUS)));
  }

  @Override
  public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
    if (index == 0) return "" + (int) 100 * SPEED_BONUS;
    return null;
  }

  @Override
  public void addPostDescriptionSection(
      TooltipMakerAPI tooltip,
      ShipAPI.HullSize hullSize,
      ShipAPI ship,
      float width,
      boolean isForModSpec) {

    ShipVariantAPI variant = ship.getVariant();

    if (variant == null) {
      // default to base variant if the ship doesn't have a proper one (when it is bought)
      variant = Global.getSettings().getVariant(ship.getId() + "_combat");
    }

    if (variant == null) return;
    if (ship.getVariant().getStationModules().isEmpty()) return;

    // title
    tooltip.addSectionHeading(txt("hm_armorStat0"), Alignment.MID, 15);

    tooltip.beginTable(
        Misc.getBasePlayerColor(),
        Misc.getDarkPlayerColor(),
        Misc.getBrightPlayerColor(),
        20f,
        true,
        true,
        new Object[] {"Name", width - 80f * 2 - 8f, "Hull", 80f, "Armor", 80f});

    for (String module : ship.getVariant().getStationModules().values()) {
      // for some insane reason, the hullspec can return null
      if (Global.getSettings().getVariant(module) == null
          || Global.getSettings().getVariant(module).getHullSpec() == null) continue;
      ShipHullSpecAPI hull = Global.getSettings().getVariant(module).getHullSpec();
      tooltip.addRow(
          Alignment.LMID,
          Misc.getTextColor(),
          hull.getHullName(),
          Alignment.MID,
          Misc.getTextColor(),
          String.valueOf(Math.round(hull.getHitpoints())),
          Alignment.MID,
          Misc.getTextColor(),
          String.valueOf(Math.round(hull.getArmorRating())));
    }
    tooltip.addTable("-", 0, 4f);

    tooltip.addPara(txt("hm_armorStat1"),10);
    for (String hullmodId : variant.getHullMods()) {
      if (hullmodEffects.containsKey(hullmodId)) {
        tooltip.addPara(Global.getSettings().getHullModSpec(hullmodId).getDisplayName(), 4f);
        hullmodEffects.get(hullmodId).addTooltipText(tooltip, ship);
      }
    }
  }

  private static final Map<String, ArmorEffect> hullmodEffects = new HashMap<>();

  static {
    hullmodEffects.put("heavyarmor", new ArmorEffect(150, 1, 1, 1, 1, 1));
    hullmodEffects.put("reinforcedhull", new ArmorEffect(0, 1, 0.72f, 1, 1, 1));
    hullmodEffects.put("TADA_lightArmor", new ArmorEffect(0, 2, 1, 1, 1, 1));
    hullmodEffects.put("TADA_reactiveArmor", new ArmorEffect(0, 1, 1, 1.25f, 1.25f, 0.66f));
  }

  public static void applyHullmodModificationsToStats(MutableShipStatsAPI stats, ShipHullSpecAPI moduleSpec, ShipVariantAPI parentVariant) {
    for (String hullmodId : parentVariant.getHullMods()) {
      if (hullmodEffects.containsKey(hullmodId)) {
        hullmodEffects.get(hullmodId).applyToStats(hullmodId, stats, moduleSpec);
      }
    }
  }

  protected static class ArmorEffect {
    public float armorDamageTakenModifier;
    public float armorDamageTakenMult;
    public float hullDamageTakenMult;
    public float energyDamageTakenMult;
    public float kineticDamageTakenMult;
    public float heDamageTakenMult;

    public ArmorEffect(float armorDamageTakenModifier, float armorDamageTakenMult, float hullDamageTakenMult, float energyDamageTakenMult, float kineticDamageTakenMult, float heDamageTakenMult) {
      this.armorDamageTakenModifier = armorDamageTakenModifier;
      this.armorDamageTakenMult = armorDamageTakenMult;
      this.hullDamageTakenMult = hullDamageTakenMult;
      this.energyDamageTakenMult = energyDamageTakenMult;
      this.kineticDamageTakenMult = kineticDamageTakenMult;
      this.heDamageTakenMult = heDamageTakenMult;
    }

    public float calcArmorDamageMult(float baseArmor) {
      return baseArmor / (baseArmor + armorDamageTakenModifier) * armorDamageTakenMult;
    }

    public void applyToStats(String buffId, MutableShipStatsAPI stats, ShipHullSpecAPI spec) {
      stats.getArmorDamageTakenMult().modifyMult(buffId, calcArmorDamageMult(spec.getArmorRating()));
      stats.getHullDamageTakenMult().modifyMult(buffId, hullDamageTakenMult);
      stats.getEnergyDamageTakenMult().modifyMult(buffId, energyDamageTakenMult);
      stats.getKineticDamageTakenMult().modifyMult(buffId, kineticDamageTakenMult);
      stats.getHighExplosiveDamageTakenMult().modifyMult(buffId, heDamageTakenMult);
    }

    public void addTooltipText(TooltipMakerAPI tooltip, ShipAPI ship) {
      tooltip.setBulletedListMode("- ");
      float armorDamageTaken = calcArmorDamageMult(ship.getHullSpec().getArmorRating());
      if (armorDamageTaken != 1) {
        String text = Misc.getRoundedValue(armorDamageTaken);
        tooltip.addPara(txt("hm_armorStatArmor", text), 4f, Misc.getHighlightColor(), text);
      }

      if (hullDamageTakenMult != 1) {
        String text = Misc.getRoundedValue(hullDamageTakenMult);
        tooltip.addPara(txt("hm_armorStatHull", text), 4f, Misc.getHighlightColor(), text);
      }

      if (energyDamageTakenMult != 1) {
        String text = Misc.getRoundedValue(energyDamageTakenMult);
        tooltip.addPara(txt("hm_armorStatEnergy", text), 4f, Misc.getHighlightColor(), text);
      }

      if (kineticDamageTakenMult != 1) {
        String text = Misc.getRoundedValue(kineticDamageTakenMult);
        tooltip.addPara(txt("hm_armorStatKinetic", text), 4f, Misc.getHighlightColor(), text);
      }

      if (heDamageTakenMult != 1) {
        String text = Misc.getRoundedValue(heDamageTakenMult);
        tooltip.addPara(txt("hm_armorStatHE", text), 4f, Misc.getHighlightColor(), text);
      }

      tooltip.setBulletedListMode(null);
    }
  }
}
