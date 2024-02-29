package data.hullmods;

import static data.scripts.util.SCY_settingsData.engineering_noncompatible;
import static data.scripts.util.SCY_txt.txt;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonalityAPI;
import com.fs.starfarer.api.combat.*;
// import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Pair;
import com.fs.starfarer.combat.entities.DamagingExplosion;
import org.lazywizard.lazylib.CollisionUtils;
import org.lazywizard.lazylib.combat.DefenseUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicIncompatibleHullmods;

import java.util.*;

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

    if(!ship.hasListenerOfClass(ExplosionOcclusionRaycast.class)) ship.addListener(new ExplosionOcclusionRaycast());
  }

  public static class ExplosionOcclusionRaycast implements DamageTakenModifier {
    public static final int NUM_RAYCASTS = 36;
    public static final String RAYCAST_KEY = "kol_module_explosion_raycast";
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
