package data.shipsystems.scripts;

import static data.scripts.util.SCY_txt.txt;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;

public class SCY_safetiesStats extends BaseShipSystemScript {

  public final float ROF_BONUS = 1f, BEAM_DAMAGE = 1f, RANGE_DROP = 500f;
  public final float FLUX_REDUCTION = 25f, FAILURE_RATE = 0.04f;

  @Override
  public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {

    stats.getBallisticRoFMult().modifyMult(id, 1f + ROF_BONUS * effectLevel);
    stats.getEnergyRoFMult().modifyMult(id, 1f + ROF_BONUS * effectLevel);
    stats.getBeamWeaponDamageMult().modifyMult(id, 1f + BEAM_DAMAGE * effectLevel);

    float threshold = stats.getWeaponRangeThreshold().getModifiedValue();
    if (threshold == 0) {
      stats.getWeaponRangeThreshold().modifyFlat(id, RANGE_DROP);
    } else if (threshold > RANGE_DROP) {
      float drop = stats.getWeaponRangeThreshold().getModifiedValue() - RANGE_DROP;
      stats.getWeaponRangeThreshold().modifyFlat(id, -drop);
    }
    stats.getWeaponRangeMultPastThreshold().modifyMult(id, 1 - effectLevel);

    stats.getEnergyWeaponFluxCostMod().modifyPercent(id, -FLUX_REDUCTION);
    stats.getBallisticWeaponFluxCostMod().modifyPercent(id, -FLUX_REDUCTION);

    float EXTRA_OVERRIDE = 0;
    if (((ShipAPI) (stats.getEntity())).getVariant().getHullMods().contains("safetyoverrides")) {
      EXTRA_OVERRIDE = 0.08f;
    } else {
      EXTRA_OVERRIDE = 0f;
    }

    stats
        .getWeaponMalfunctionChance()
        .modifyFlat(id, (FAILURE_RATE + EXTRA_OVERRIDE) * effectLevel);
  }

  @Override
  public void unapply(MutableShipStatsAPI stats, String id) {
    stats.getBallisticRoFMult().unmodify(id);
    stats.getEnergyRoFMult().unmodify(id);
    stats.getBeamWeaponDamageMult().unmodify(id);
    stats.getEnergyWeaponFluxCostMod().unmodify(id);
    stats.getBallisticWeaponFluxCostMod().unmodify(id);
    stats.getWeaponMalfunctionChance().unmodify(id);
    stats.getWeaponRangeMultPastThreshold().unmodify(id);
    stats.getWeaponRangeThreshold().unmodify(id);
  }

  @Override
  public StatusData getStatusData(int index, State state, float effectLevel) {
    float mult = 1f + ROF_BONUS * effectLevel;
    float bonusPercent = (int) ((mult - 1f) * 100f);
    if (index == 0) {
      return new StatusData(txt("sstm_switch0") + (int) bonusPercent + txt("%"), false);
    }
    if (index == 1) {
      return new StatusData(txt("sstm_switch1") + (int) FLUX_REDUCTION + txt("%"), false);
    }
    if (index == 2) {
      return new StatusData(
          txt("sstm_switch2") + (int) (1000 - (RANGE_DROP * effectLevel)) + txt("su"), false);
    }
    return null;
  }
}
