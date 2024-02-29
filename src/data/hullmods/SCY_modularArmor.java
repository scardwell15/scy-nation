package data.hullmods;

import static data.scripts.util.SCY_txt.txt;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Pair;
import com.fs.starfarer.combat.entities.DamagingExplosion;
import org.lazywizard.lazylib.CollisionUtils;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.DefenseUtils;
import org.lwjgl.util.vector.Vector2f;

import java.util.*;

public class SCY_modularArmor extends BaseHullMod {

  private final String SCY_ARMOR_MOD = "SCY_ARMOR_MOD";
  private final float SPEED_BONUS = 0.25f;

  @Override
  public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
    if(!ship.hasListenerOfClass(ExplosionOcclusionRaycast.class)) ship.addListener(new ExplosionOcclusionRaycast());
  }

  public static class ExplosionOcclusionRaycast implements DamageTakenModifier {
    public static final int NUM_RAYCASTS = 36;
    public static final String RAYCAST_KEY = "scy_module_explosion_raycast";
    @Override
    public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point, boolean shieldHit) {
      ShipAPI ship = (ShipAPI) target;
      ShipAPI parent = ship.getParentStation() == null ? ship : ship.getParentStation();

      if (param instanceof DamagingExplosion || param instanceof MissileAPI) {
        DamagingProjectileAPI projectile = (DamagingProjectileAPI) param;


        HashMap<DamagingProjectileAPI, HashMap<String, Float>> explosionMap = (HashMap<DamagingProjectileAPI, HashMap<String, Float>>) parent.getCustomData().get(RAYCAST_KEY);

        // if this is the explosion from the missile, look up the cached result and use that
        if(projectile instanceof DamagingExplosion && explosionMap != null){
          for(DamagingProjectileAPI pastProjectile : explosionMap.keySet()){
            if (pastProjectile instanceof MissileAPI && pastProjectile.getSource() == projectile.getSource() &&
                    MathUtils.getDistanceSquared(pastProjectile.getLocation(), projectile.getSpawnLocation()) < 1f){
              projectile = pastProjectile;
              break;
            }
          }
        }

        generateExplosionRayhitMap(projectile, damage, parent);
        explosionMap = (HashMap<DamagingProjectileAPI, HashMap<String, Float>>) parent.getCustomData().get(RAYCAST_KEY);

        damage.getModifier().modifyMult(this.getClass().getName(), explosionMap.get(projectile).get(ship.getId()));

        return this.getClass().getName();
      }
      return null;
    }

    public void generateExplosionRayhitMap(DamagingProjectileAPI projectile, DamageAPI damage, ShipAPI parent){

      if(!parent.getCustomData().containsKey(RAYCAST_KEY) || !(parent.getCustomData().get(RAYCAST_KEY) instanceof HashMap)){
        parent.setCustomData(RAYCAST_KEY, new HashMap<DamagingProjectileAPI, HashMap<String, Float>>());
      }
      HashMap<DamagingProjectileAPI, HashMap<String, Float>> explosionMap = (HashMap<DamagingProjectileAPI, HashMap<String, Float>>) parent.getCustomData().get(RAYCAST_KEY);
      if(explosionMap.containsKey(projectile)){
        return;
      }

      HashMap<String, Float> damageReductionMap = new HashMap<>();
      explosionMap.put(projectile, damageReductionMap);
      damageReductionMap.put("RemovalTime", Global.getCombatEngine().getTotalElapsedTime(false) + 1f);

      // remove old cached explosions
      for(Iterator<Map.Entry<DamagingProjectileAPI, HashMap<String, Float>>> pastProjectileIterator = explosionMap.entrySet().iterator(); pastProjectileIterator.hasNext();){
        Map.Entry<DamagingProjectileAPI, HashMap<String, Float>> pastProjectile = pastProjectileIterator.next();
        if (pastProjectile.getValue().get("RemovalTime") < Global.getCombatEngine().getTotalElapsedTime(false)) pastProjectileIterator.remove();
      }

      // init all occlusions
      List<ShipAPI> potentialOcclusions = parent.getChildModulesCopy();
      potentialOcclusions.add(parent);
      HashMap<String, Integer> hitsMap = new HashMap<>();
      for(ShipAPI occlusion: potentialOcclusions){
        damageReductionMap.put(occlusion.getId(), 1f);
        hitsMap.put(occlusion.getId(), 0);
      }

      // skip if not an explosion
      Vector2f explosionLocation;
      List<CombatEntityAPI> damagedAlready = new ArrayList<>();
      float radius;
      if (projectile instanceof MissileAPI || projectile instanceof DamagingExplosion) {
        if(projectile instanceof MissileAPI ){
          MissileAPI missile = (MissileAPI) projectile;
          if(missile.getDamagedAlready() != null) damagedAlready = missile.getDamagedAlready();
          explosionLocation = missile.getLocation();
          radius = missile.getSpec().getExplosionRadius();
        } else{
          DamagingExplosion explosion = (DamagingExplosion) projectile;
          if(explosion.getDamagedAlready() != null) damagedAlready = explosion.getDamagedAlready();
          explosionLocation = explosion.getLocation();
          radius = explosion.getCollisionRadius();
        }
      } else{
        return;
      }


      // remove out of range occlusions
      for (Iterator<ShipAPI> occlusionIter = potentialOcclusions.iterator(); occlusionIter.hasNext();){
        ShipAPI occlusion = occlusionIter.next();
        float explosionDistance = Misc.getTargetingRadius(explosionLocation, occlusion, false) + radius;
        float moduleDistance = MathUtils.getDistanceSquared(explosionLocation, occlusion.getLocation());
        if(moduleDistance > (explosionDistance * explosionDistance)){
          occlusionIter.remove();
        }
      }

      // skip if there is 0 or 1 ship in range
      if(potentialOcclusions.isEmpty() || potentialOcclusions.size() == 1){
        return;
      }

      // if more then 1 thing is in range, then raycast to check for explosion mults

      int totalRayHits = 0;

      List<Vector2f> rayEndpoints = MathUtils.getPointsAlongCircumference(explosionLocation, radius, NUM_RAYCASTS, 0f);
      for(Vector2f endpoint : rayEndpoints){
        float closestDistanceSquared = radius * radius;
        String occlusionID = null;
        for(ShipAPI occlusion : potentialOcclusions){ //  for each ray loop past all occlusions
          Vector2f pointOnModuleBounds = CollisionUtils.getCollisionPoint(explosionLocation, endpoint, occlusion);

          if(pointOnModuleBounds != null){ // if one is hit
            float occlusionDistance = MathUtils.getDistanceSquared(explosionLocation, pointOnModuleBounds);
            if(occlusionDistance < closestDistanceSquared){ // check the distance, if its shorter remember it
              occlusionID = occlusion.getId();
              closestDistanceSquared = occlusionDistance;
            }
          }
        }
        if(occlusionID != null){ // only not null if something is hit, in that case inc TotalRayHits
          totalRayHits++;
          hitsMap.put(occlusionID, hitsMap.get(occlusionID) + 1);
        }
      }
      if(totalRayHits == 0) return;

      float overkillDamage = 0f;
      for(ShipAPI occlusion : potentialOcclusions){
        if(occlusion == parent) continue; // special case the parent
        // calculate and set the damage mult
        float rayHits = (float) hitsMap.get(occlusion.getId());
        float damageMult = Math.min(1f, Math.max(rayHits / totalRayHits, rayHits /((float) NUM_RAYCASTS /2)));
        damageReductionMap.put(occlusion.getId(), damageMult);

        // calculate the actual hp left over after the hit, if damage > hp, note down the overflow
        float moduleArmor = getCurrentArmorRating(occlusion);
        Pair<Float, Float> damageResult = damageAfterArmor(damage.getType(), damage.getDamage()*damageMult, damage.getDamage(), moduleArmor, occlusion);
        float hullDamage = damageResult.two;

        if(hullDamage > occlusion.getHitpoints() && !damagedAlready.contains(occlusion)){
          overkillDamage += hullDamage - occlusion.getHitpoints();
        }
      }

      // do the same mult calc for the parent, except also subtract overkill from the reduction
      float damageMult = (float) hitsMap.get(parent.getId()) / totalRayHits;
      damageReductionMap.put(parent.getId(), ((damage.getDamage() * damageMult) + overkillDamage)/damage.getDamage());
    }

    public static float getCurrentArmorRating(ShipAPI ship){
      if (ship == null || !Global.getCombatEngine().isEntityInPlay(ship)) {
        return 0f;
      }
      ArmorGridAPI armorGrid = ship.getArmorGrid();
      float[][] armorGridGrid = armorGrid.getGrid();
      List<Float> armorList = new ArrayList<>();
      org.lwjgl.util.Point worstPoint = DefenseUtils.getMostDamagedArmorCell(ship);
      if(worstPoint != null){
        float totalArmor = 0;
        for (int x = 0; x < armorGridGrid.length; x++) {
          for (int y = 0; y < armorGridGrid[x].length; y++) {
            armorList.add(armorGridGrid[x][y]);
          }
        }
        Collections.sort(armorList);
        for(int i = 0; i < 21; i++){
          if(i < 9) totalArmor += armorList.get(i);
          else  totalArmor += armorList.get(i)/2;
        }
        return totalArmor;
      } else{
        return armorGrid.getMaxArmorInCell() * 15f;
      }
    }

    public static Pair<Float, Float> damageAfterArmor(DamageType damageType, float damage, float hitStrength, float armorValue, ShipAPI ship){
      MutableShipStatsAPI stats = ship.getMutableStats();

      float armorMultiplier = stats.getArmorDamageTakenMult().getModifiedValue();
      float effectiveArmorMult = stats.getEffectiveArmorBonus().getMult();
      float hullMultiplier = stats.getHullDamageTakenMult().getModifiedValue();
      float minArmor = stats.getMinArmorFraction().getModifiedValue();
      float maxDR = stats.getMaxArmorDamageReduction().getModifiedValue();

      armorValue = Math.max(minArmor * ship.getArmorGrid().getArmorRating(), armorValue);
      switch (damageType) {
        case FRAGMENTATION:
          armorMultiplier *= (0.25f * stats.getFragmentationDamageTakenMult().getModifiedValue());
          hullMultiplier *= stats.getFragmentationDamageTakenMult().getModifiedValue();
          break;
        case KINETIC:
          armorMultiplier *= (0.5f * stats.getKineticDamageTakenMult().getModifiedValue());
          hullMultiplier *= stats.getKineticDamageTakenMult().getModifiedValue();
          break;
        case HIGH_EXPLOSIVE:
          armorMultiplier *= (2f * stats.getHighExplosiveDamageTakenMult().getModifiedValue());
          hullMultiplier *= stats.getHighExplosiveDamageTakenMult().getModifiedValue();
          break;
        case ENERGY:
          armorMultiplier *= stats.getEnergyDamageTakenMult().getModifiedValue();
          hullMultiplier *= stats.getEnergyDamageTakenMult().getModifiedValue();
          break;
      }

      damage *= Math.max((1f - maxDR), ((hitStrength * armorMultiplier) / (armorValue * effectiveArmorMult + hitStrength * armorMultiplier)));

      float armorDamage = damage * armorMultiplier;
      float hullDamage = 0;
      if (armorDamage > armorValue){
        hullDamage = ((armorDamage - armorValue)/armorDamage) * damage * hullMultiplier;
      }

      return new Pair<>(armorDamage, hullDamage);
    }
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
