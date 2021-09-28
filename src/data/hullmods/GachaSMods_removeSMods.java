package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import org.apache.log4j.Logger;

import java.awt.*;
import java.util.ArrayList;
import java.util.Random;

import static data.hullmods.GachaSMods_randomSMod.*;
import static data.scripts.GachaSMods_Utils.*;


public class GachaSMods_removeSMods extends BaseHullMod {

    private static final Logger log = Global.getLogger(GachaSMods_removeSMods.class);

    private final String HULLMOD_CONFLICT = getString("gachaConflict");
    private final String NO_SMODS = getString("removeInapplicable"); // "Ship is at the built-in hullmod limit"

    // yeah, yeah they only instantiate once per class blah blah blah
    // they're updated/cleared any time they're used anyways
    private final ArrayList<String> removedMods = new ArrayList<>(); //cleared every time
    private int numSP = -1; //constant per character

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {

        if ((ship.getVariant().getSMods().size() == Misc.getMaxPermanentMods(ship)
                && !ship.getVariant().hasHullMod(PLACEHOLDER_ID + "0"))
                || ship.getVariant().getSMods().size() > Misc.getMaxPermanentMods(ship)) {
            ship.getMutableStats().getDynamic().getMod(Stats.MAX_PERMANENT_HULLMODS_MOD).modifyFlat(id, 1);
        }

        // initialize all these dumb variables
        ShipVariantAPI variant = ship.getVariant();
        Random random = new Random();
        String seedKey = null;
        long savedSeed;
        int minSModsToRemove = boundMinMaxSModsToRemove(Global.getSettings().getInt(MIN_REMOVED_SMODS));
        int maxSModsToRemove = Math.max(minSModsToRemove, boundMinMaxSModsToRemove(Global.getSettings().getInt(MAX_REMOVED_SMODS)));

        if (ship.getFleetMemberId() != null) {
            seedKey = SAVED_SEED + "_" + ship.getFleetMemberId();
        }

        // main script that fires when the hullmod has been s-modded
        if (variant.getSMods().contains(spec.getId())) {
            // anti-save scum stuff
            if (Global.getSettings().getBoolean(NO_SAVE_SCUMMING_SETTING)) {
                savedSeed = getSeed(ship, seedKey);
                random = new Random(savedSeed);
                //log.info("Loaded seed " + savedSeed);
            }
            numSP = Global.getSector().getPlayerPerson().getStats().getStoryPoints();
            variant.removePermaMod(spec.getId());
            // dumb way to continue checks during confirmation screen, because otherwise the script stops when it's removed
            // also I don't make it a non-s-mod perma-mod because then you can't s-mod multiple times in a row
            variant.addMod(spec.getId());

            //log.info("Removing s-mods...");
            // the picker is still pseudo-random (random but I think predictable periodic cycle of (numSMods choose sModsToRemove))
            // under no save-scumming rules because when cancelling it changes the order of the s-mods
            // fixing that means adding like a TreeMap comparator thing for sorting before picking?
            // todo: fuck.
            WeightedRandomPicker<String> picker = new WeightedRandomPicker<>(random);
            // need to not add placeholders into the picker if repeated s-mod removal is done
            for (String sMod : variant.getSMods()) {
                if (!sMod.startsWith(MOD_ID)) {
                    picker.add(sMod);
                }
            }

            // remove at least min(numSMods, minSModsToRemove from settings)
            // remove at most min(numSMods, maxSModsToRemove from settings)
            // I think I added like four fail-safes here, so it's super redundant but oh well
            int tempMin = Math.min(minSModsToRemove, picker.getItems().size());
            int tempMax = Math.max(tempMin, Math.min(maxSModsToRemove, picker.getItems().size()));
            //log.info("tempMin: " + tempMin + ", tempMax: " + tempMax);
            int numSModsToRemove = tempMin + random.nextInt(Math.max(1, tempMax - tempMin + 1));
            log.info("Removing " + numSModsToRemove + " s-mods");
            for (int i = 1; i <= numSModsToRemove; i++) {
                String pickId = picker.pick(random);
                picker.remove(pickId);
                variant.removePermaMod(pickId);
                removedMods.add(pickId); // may need to add them back if we cancel, so remember them
                log.info("Removing " + pickId);

                // we need to add # removed s-mods + 1 or else you can't press confirm
                adjustHullModForSModding(PLACEHOLDER_ID + i, ship.getHullSize(), false, random);
                variant.addPermaMod(PLACEHOLDER_ID + i, true);
            }

            // need this so that you can actually confirm the s-mod process
            adjustHullModForSModding(PLACEHOLDER_ID + "0", ship.getHullSize(), false, random);
            if (variant.hasHullMod(PLACEHOLDER_ID + "0")) {
                variant.removeMod(PLACEHOLDER_ID + "0");
            }
            variant.addPermaMod(PLACEHOLDER_ID + "0", true);
            // blocks the s-modding process, so you can't do it more than once
            spec.addTag(Tags.HULLMOD_NO_BUILD_IN);
        }

        // will usually do nothing, exceptions being when it has been s-modded / s-modding has been cancelled
        // had to be done because the confirmation screen also fires this script
        // meaning weird stuff would happen when you cancel the s-modding process
        // in the case that the player cancels the s-mod process, re-add the removed hullmods
        if (variant.getNonBuiltInHullmods().contains(spec.getId())) {
            if (variant.getNonBuiltInHullmods().contains(PLACEHOLDER_ID + "0")) {
                //log.info("Cancelled s-mod removal");
                for (String removedModId : removedMods) {
                    variant.addPermaMod(removedModId, true);
                    //log.info("Re-added " + removedModId);
                }

                for (int i = 0; i <= maxSModsToRemove; i++) {
                    if (variant.hasHullMod(PLACEHOLDER_ID + i)) {
                        variant.removeMod(PLACEHOLDER_ID + i);
                        restoreHullMod(PLACEHOLDER_ID + i);
                        //log.info("Removing hullmod " + PLACEHOLDER_ID + i);
                    }
                }
                removedMods.clear();
                spec.getTags().remove(Tags.HULLMOD_NO_BUILD_IN);
            }
            // check confirmation by number of story points
            // todo: there's an annoying case where you can have 0 s-mods left but the hullmod is still equipped and you can s-mod it
            // todo: probably the best way to deal with that is to try to remove it in the PLACEHOLDER script if no non-placeholder s-mods are detected
            // for now though, fuck the pl*yers if you do this you lose your story point for nothing
            if (Global.getSector().getPlayerPerson().getStats().getStoryPoints() < numSP) {
                //log.info("Confirmed s-mod removal");
                numSP = Global.getSector().getPlayerPerson().getStats().getStoryPoints();

                // it gets removed upon switching away to another ship, but will remain with any action that checks the variant
                // (i.e. anything that isn't swapping to another ship or leaving the refit screen)
                // it's very necessary to remove this mod for save compatibility, so we'll do our best
                for (int i = 0; i <= maxSModsToRemove; i++) {
                    variant.removePermaMod(PLACEHOLDER_ID + i);
                    restoreHullMod(PLACEHOLDER_ID + i);
                }
                removedMods.clear();
                spec.getTags().remove(Tags.HULLMOD_NO_BUILD_IN);

                // update the seed. we need this because you can undergo the removal process multiple times in a row
                // I don't actually know if this is necessary because changing the number of s-mods will change the picker and stuff but just in case
                savedSeed = random.nextLong();
                Global.getSector().getPersistentData().put(seedKey, savedSeed);
            }
        }
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        if (ship.getVariant().getSMods().size() == 0) {
            return false;
        }
        if (shipHasOtherModInCategory(ship, spec.getId(), MOD_ID)) {
            return false;
        }
        return true;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship.getVariant().getSMods().size() == 0) {
            return NO_SMODS;
        }
        if (shipHasOtherModInCategory(ship, spec.getId(), MOD_ID)) {
            return HULLMOD_CONFLICT + getOtherModsInCategory(ship, spec.getId(), MOD_ID);
        }
        return null;
    }

    @Override
    public Color getNameColor() {
        return Misc.getHighlightColor();
    }

    // hard limits 'cuz setting it dynamically would be very annoying
    public int boundMinMaxSModsToRemove(int minOrMaxSModsToRemove) {
        if (minOrMaxSModsToRemove < 0) {
            minOrMaxSModsToRemove = 0;
        } else if (minOrMaxSModsToRemove > 10) {
            minOrMaxSModsToRemove = 10;
        }
        return minOrMaxSModsToRemove;
    }
}
