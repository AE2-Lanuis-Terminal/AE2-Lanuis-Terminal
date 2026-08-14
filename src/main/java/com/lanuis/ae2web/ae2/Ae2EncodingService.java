package com.lanuis.ae2web.ae2;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.core.definitions.AEItems;
import appeng.crafting.pattern.AEProcessingPattern;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 样板编码：消耗 ME 空白样板，经 {@link PatternDetailsHelper} 编码后写入供应器空槽；失败回滚空白样板。
 */
public final class Ae2EncodingService {
    private Ae2EncodingService() {
    }

    private static final AbstractContainerMenu DUMMY_MENU = new AbstractContainerMenu(null, -1) {
        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    };

    public static JsonObject status(IGrid grid) {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("blankPatterns", Long.toString(countBlankPatterns(grid)));
        JsonObject modes = new JsonObject();
        modes.addProperty("crafting", true);
        modes.addProperty("processing", true);
        modes.addProperty("smithing", true);
        modes.addProperty("stonecutting", true);
        root.add("modes", modes);
        return root;
    }

    public static JsonObject resolve(MinecraftServer server, IGrid grid, JsonObject body) {
        String mode = str(body, "mode");
        if (mode.isEmpty()) {
            return err("bad_request", "mode required");
        }
        ServerLevel level = server.overworld();
        try {
            return switch (mode) {
                case "crafting" -> resolveCrafting(level, body, false);
                case "processing" -> resolveProcessing(body, false);
                case "smithing" -> resolveSmithing(level, body, false);
                case "stonecutting" -> resolveStonecutting(level, body, false);
                default -> err("bad_request", "Unknown mode: " + mode);
            };
        } catch (IllegalArgumentException e) {
            return previewFail(e.getMessage() != null ? e.getMessage() : "invalid_item");
        }
    }

    public static JsonObject stonecuttingOptions(MinecraftServer server, JsonObject body) {
        JsonObject inputSlot = body.has("input") && body.get("input").isJsonObject()
                ? body.getAsJsonObject("input")
                : new JsonObject();
        AEItemKey input;
        try {
            input = parseSlotKey(inputSlot);
        } catch (IllegalArgumentException e) {
            return err("invalid_item", "Unknown input");
        }
        if (input == null) {
            return err("invalid_item", "Unknown input");
        }
        ServerLevel level = server.overworld();
        SimpleContainer container = new SimpleContainer(1);
        container.setItem(0, input.toStack());
        JsonArray options = new JsonArray();
        for (StonecutterRecipe recipe : level.getRecipeManager().getRecipesFor(RecipeType.STONECUTTING, container, level)) {
            ItemStack outStack = recipe.getResultItem(level.registryAccess());
            AEItemKey outKey = AEItemKey.of(outStack);
            if (outKey == null) {
                continue;
            }
            JsonObject opt = new JsonObject();
            opt.addProperty("recipeId", recipe.getId().toString());
            opt.add("output", Ae2QueryService.toStackDto(outKey, Math.max(1, outStack.getCount()), false));
            options.add(opt);
        }
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.add("options", options);
        return root;
    }

    public static JsonObject encode(MinecraftServer server, IGrid grid, UUID playerUuid, String encoderName, JsonObject body) {
        String mode = str(body, "mode");
        String providerId = str(body, "providerId");
        if (mode.isEmpty()) {
            return err("bad_request", "mode required");
        }
        if (providerId.isEmpty()) {
            return err("bad_request", "providerId required");
        }
        Integer slotIndex = body.has("slotIndex") && body.get("slotIndex").isJsonPrimitive()
                ? body.get("slotIndex").getAsInt()
                : null;

        ServerLevel level = server.overworld();
        JsonObject preview;
        try {
            preview = switch (mode) {
                case "crafting" -> resolveCrafting(level, body, true);
                case "processing" -> resolveProcessing(body, true);
                case "smithing" -> resolveSmithing(level, body, true);
                case "stonecutting" -> resolveStonecutting(level, body, true);
                default -> err("bad_request", "Unknown mode: " + mode);
            };
        } catch (IllegalArgumentException e) {
            return err("invalid_item", e.getMessage() != null ? e.getMessage() : "invalid_item");
        }
        if (!preview.has("ok") || !preview.get("ok").getAsBoolean()) {
            return preview;
        }
        if (!preview.has("canEncode") || !preview.get("canEncode").getAsBoolean()) {
            return err("invalid_recipe", preview.has("warning") ? preview.get("warning").getAsString() : "Cannot encode");
        }

        ItemStack encoded;
        try {
            encoded = buildEncodedStack(level, body, mode);
        } catch (IllegalStateException e) {
            return parseCodedErr(e);
        } catch (IllegalArgumentException e) {
            return err("invalid_item", e.getMessage() != null ? e.getMessage() : "invalid_item");
        }
        if (encoded == null || encoded.isEmpty()) {
            return err("invalid_recipe", "Encode produced empty stack");
        }
        if (encoderName != null && !encoderName.isBlank()) {
            CompoundTag tag = encoded.getOrCreateTag();
            tag.putString("encoder", encoderName.trim());
        }

        MEStorage storage = grid.getStorageService().getInventory();
        AEItemKey blankKey = AEItemKey.of(AEItems.BLANK_PATTERN.asItem());
        if (blankKey == null) {
            return err("no_blank_pattern", "Blank pattern key unavailable");
        }
        IActionSource src;
        try {
            src = Ae2QueryService.actionSource(server, playerUuid);
        } catch (IllegalStateException e) {
            return err(e.getMessage() != null ? e.getMessage() : "action_source_unavailable", "Cannot create action source");
        }
        long extracted = storage.extract(blankKey, 1, Actionable.MODULATE, src);
        if (extracted < 1) {
            return err("no_blank_pattern", "No blank patterns in ME");
        }

        int writtenSlot;
        try {
            writtenSlot = Ae2PatternBoardService.insertEncodedPattern(grid, providerId, encoded, slotIndex);
        } catch (IllegalStateException e) {
            storage.insert(blankKey, 1, Actionable.MODULATE, src);
            return parseCodedErr(e);
        } catch (Throwable t) {
            storage.insert(blankKey, 1, Actionable.MODULATE, src);
            return err("encode_failed", t.getMessage() != null ? t.getMessage() : "Insert failed");
        }

        Level decodeLevel = Ae2PatternBoardService.levelForProvider(grid, providerId);
        if (decodeLevel == null) {
            decodeLevel = level;
        }
        IPatternDetails details = PatternDetailsHelper.decodePattern(encoded, decodeLevel);
        JsonObject providerDto = Ae2PatternBoardService.providerDtoById(grid, providerId);
        JsonObject patternDto = details == null
                ? null
                : Ae2PatternService.toPatternDtoPublic(details, providerDto, writtenSlot);

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("providerId", providerId);
        root.addProperty("slotIndex", writtenSlot);
        if (patternDto != null) {
            root.add("pattern", patternDto);
        }
        root.addProperty("message", "Encoded");
        return root;
    }

    private static ItemStack buildEncodedStack(ServerLevel level, JsonObject body, String mode) {
        boolean substitute = bool(body, "substitute", false);
        boolean substituteFluids = bool(body, "substituteFluids", true);
        List<JsonObject> inputs = inputsOf(body);
        List<JsonObject> outputs = outputsOf(body);
        return switch (mode) {
            case "crafting" -> {
                CraftingMatch match = matchCrafting(level, inputs);
                if (match == null) {
                    throw new IllegalStateException("invalid_recipe:No matching crafting recipe");
                }
                yield PatternDetailsHelper.encodeCraftingPattern(
                        match.recipe, match.ingredients, match.output, substitute, substituteFluids);
            }
            case "processing" -> {
                GenericStack[] inArr = toGenericSparse(inputs, AEProcessingPattern.MAX_INPUT_SLOTS);
                GenericStack[] outArr = toGenericSparse(outputs, AEProcessingPattern.MAX_OUTPUT_SLOTS);
                if (!hasAny(inArr) || outArr.length == 0 || outArr[0] == null) {
                    throw new IllegalStateException("invalid_recipe:Need inputs and primary output");
                }
                yield PatternDetailsHelper.encodeProcessingPattern(inArr, outArr);
            }
            case "smithing" -> {
                AEItemKey template = requireKey(inputs, 0, "template");
                AEItemKey base = requireKey(inputs, 1, "base");
                AEItemKey addition = requireKey(inputs, 2, "addition");
                SimpleContainer container = new SimpleContainer(3);
                container.setItem(0, template.toStack());
                container.setItem(1, base.toStack());
                container.setItem(2, addition.toStack());
                SmithingRecipe recipe = level.getRecipeManager()
                        .getRecipeFor(RecipeType.SMITHING, container, level)
                        .orElse(null);
                if (recipe == null) {
                    throw new IllegalStateException("invalid_recipe:No matching smithing recipe");
                }
                AEItemKey out = AEItemKey.of(recipe.assemble(container, level.registryAccess()));
                if (out == null) {
                    throw new IllegalStateException("invalid_recipe:Smithing output invalid");
                }
                yield PatternDetailsHelper.encodeSmithingTablePattern(recipe, template, base, addition, out, substitute);
            }
            case "stonecutting" -> {
                AEItemKey input = requireKey(inputs, 0, "input");
                String recipeId = str(body, "recipeId");
                if (recipeId.isEmpty()) {
                    throw new IllegalStateException("invalid_recipe:recipeId required");
                }
                ResourceLocation id = ResourceLocation.tryParse(recipeId);
                if (id == null) {
                    throw new IllegalStateException("invalid_recipe:Bad recipeId");
                }
                SimpleContainer container = new SimpleContainer(1);
                container.setItem(0, input.toStack());
                StonecutterRecipe recipe = level.getRecipeManager()
                        .getRecipesFor(RecipeType.STONECUTTING, container, level)
                        .stream()
                        .filter(r -> r.getId().equals(id))
                        .findFirst()
                        .orElse(null);
                if (recipe == null) {
                    throw new IllegalStateException("invalid_recipe:Stonecutting recipe not found");
                }
                AEItemKey out = AEItemKey.of(recipe.getResultItem(level.registryAccess()));
                if (out == null) {
                    throw new IllegalStateException("invalid_recipe:Stonecutting output invalid");
                }
                yield PatternDetailsHelper.encodeStonecuttingPattern(recipe, input, out, substitute);
            }
            default -> throw new IllegalStateException("bad_request:Unknown mode");
        };
    }

    private static JsonObject resolveCrafting(ServerLevel level, JsonObject body, boolean strict) {
        CraftingMatch match = matchCrafting(level, inputsOf(body));
        if (match == null) {
            return strict ? err("invalid_recipe", "No matching crafting recipe") : previewFail("No matching crafting recipe");
        }
        JsonObject root = previewOk();
        root.addProperty("recipeId", match.recipe.getId().toString());
        root.addProperty("craftingShape", match.recipe instanceof ShapedRecipe ? "shaped" : "shapeless");
        AEItemKey outKey = AEItemKey.of(match.output);
        if (outKey == null) {
            return strict ? err("invalid_recipe", "Crafting output invalid") : previewFail("Crafting output invalid");
        }
        JsonObject out = Ae2QueryService.toStackDto(outKey, Math.max(1, match.output.getCount()), false);
        root.add("primaryOutput", out);
        JsonArray outs = new JsonArray();
        outs.add(out);
        root.add("outputs", outs);
        return root;
    }

    private static JsonObject resolveProcessing(JsonObject body, boolean strict) {
        List<JsonObject> inputs = inputsOf(body);
        List<JsonObject> outputs = outputsOf(body);
        boolean hasIn = false;
        for (JsonObject s : inputs) {
            if (slotRef(s) != null) {
                hasIn = true;
                break;
            }
        }
        List<JsonObject> outDtos = new ArrayList<>();
        for (JsonObject s : outputs) {
            AEItemKey key = parseSlotKey(s);
            if (key == null) {
                continue;
            }
            outDtos.add(Ae2QueryService.toStackDto(key, amountOf(s, 1), false));
        }
        if (!hasIn || outDtos.isEmpty()) {
            return strict
                    ? err("invalid_recipe", "Need inputs and outputs")
                    : previewFail("Need inputs and outputs");
        }
        JsonObject root = previewOk();
        root.add("primaryOutput", outDtos.get(0));
        JsonArray arr = new JsonArray();
        outDtos.forEach(arr::add);
        root.add("outputs", arr);
        return root;
    }

    private static JsonObject resolveSmithing(ServerLevel level, JsonObject body, boolean strict) {
        List<JsonObject> inputs = inputsOf(body);
        AEItemKey template = parseSlotKey(at(inputs, 0));
        AEItemKey base = parseSlotKey(at(inputs, 1));
        AEItemKey addition = parseSlotKey(at(inputs, 2));
        if (template == null || base == null || addition == null) {
            return strict
                    ? err("invalid_recipe", "Need template, base, addition")
                    : previewFail("Need template, base, addition");
        }
        SimpleContainer container = new SimpleContainer(3);
        container.setItem(0, template.toStack());
        container.setItem(1, base.toStack());
        container.setItem(2, addition.toStack());
        SmithingRecipe recipe = level.getRecipeManager()
                .getRecipeFor(RecipeType.SMITHING, container, level)
                .orElse(null);
        if (recipe == null) {
            return strict ? err("invalid_recipe", "No matching smithing recipe") : previewFail("No matching smithing recipe");
        }
        ItemStack outStack = recipe.assemble(container, level.registryAccess());
        AEItemKey out = AEItemKey.of(outStack);
        if (out == null) {
            return strict ? err("invalid_recipe", "Smithing output invalid") : previewFail("Smithing output invalid");
        }
        JsonObject root = previewOk();
        root.addProperty("recipeId", recipe.getId().toString());
        JsonObject outDto = Ae2QueryService.toStackDto(out, Math.max(1, outStack.getCount()), false);
        root.add("primaryOutput", outDto);
        JsonArray outs = new JsonArray();
        outs.add(outDto);
        root.add("outputs", outs);
        return root;
    }

    private static JsonObject resolveStonecutting(ServerLevel level, JsonObject body, boolean strict) {
        List<JsonObject> inputs = inputsOf(body);
        AEItemKey input = parseSlotKey(at(inputs, 0));
        if (input == null) {
            return strict ? err("invalid_recipe", "Need input") : previewFail("Need input");
        }
        String recipeId = str(body, "recipeId");
        if (recipeId.isEmpty()) {
            return strict ? err("invalid_recipe", "Select a recipe") : previewFail("Select a recipe");
        }
        ResourceLocation id = ResourceLocation.tryParse(recipeId);
        if (id == null) {
            return strict ? err("invalid_recipe", "Bad recipeId") : previewFail("Bad recipeId");
        }
        SimpleContainer container = new SimpleContainer(1);
        container.setItem(0, input.toStack());
        StonecutterRecipe recipe = level.getRecipeManager()
                .getRecipesFor(RecipeType.STONECUTTING, container, level)
                .stream()
                .filter(r -> r.getId().equals(id))
                .findFirst()
                .orElse(null);
        if (recipe == null) {
            return strict ? err("invalid_recipe", "Stonecutting recipe not found") : previewFail("Stonecutting recipe not found");
        }
        ItemStack outStack = recipe.getResultItem(level.registryAccess());
        AEItemKey out = AEItemKey.of(outStack);
        if (out == null) {
            return strict ? err("invalid_recipe", "Stonecutting output invalid") : previewFail("Stonecutting output invalid");
        }
        JsonObject root = previewOk();
        root.addProperty("recipeId", recipe.getId().toString());
        JsonObject outDto = Ae2QueryService.toStackDto(out, Math.max(1, outStack.getCount()), false);
        root.add("primaryOutput", outDto);
        JsonArray outs = new JsonArray();
        outs.add(outDto);
        root.add("outputs", outs);
        return root;
    }

    private record CraftingMatch(CraftingRecipe recipe, ItemStack[] ingredients, ItemStack output) {
    }

    private static CraftingMatch matchCrafting(ServerLevel level, List<JsonObject> inputs) {
        ItemStack[] ingredients = new ItemStack[9];
        boolean any = false;
        for (int i = 0; i < 9; i++) {
            ingredients[i] = ItemStack.EMPTY;
        }
        for (JsonObject slot : inputs) {
            int index = slot.has("index") && slot.get("index").isJsonPrimitive()
                    ? slot.get("index").getAsInt()
                    : -1;
            if (index < 0 || index > 8) {
                continue;
            }
            AEItemKey key = parseSlotKey(slot);
            if (key == null) {
                continue;
            }
            ingredients[index] = key.toStack(1);
            any = true;
        }
        // 若未带 index，按出现顺序填入非空格
        if (!any) {
            int cursor = 0;
            for (JsonObject slot : inputs) {
                if (cursor > 8) {
                    break;
                }
                if (slot.has("index")) {
                    continue;
                }
                AEItemKey key = parseSlotKey(slot);
                if (key == null) {
                    cursor++;
                    continue;
                }
                ingredients[cursor] = key.toStack(1);
                any = true;
                cursor++;
            }
        }
        if (!any) {
            return null;
        }
        CraftingContainer container = new TransientCraftingContainer(DUMMY_MENU, 3, 3);
        for (int i = 0; i < 9; i++) {
            container.setItem(i, ingredients[i] == null || ingredients[i].isEmpty() ? ItemStack.EMPTY : ingredients[i].copy());
        }
        CraftingRecipe recipe = level.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, container, level)
                .orElse(null);
        if (recipe == null) {
            return null;
        }
        ItemStack output = recipe.assemble(container, level.registryAccess());
        if (output == null || output.isEmpty()) {
            return null;
        }
        return new CraftingMatch(recipe, ingredients, output);
    }

    private static long countBlankPatterns(IGrid grid) {
        MEStorage storage = grid.getStorageService().getInventory();
        AEItemKey blank = AEItemKey.of(AEItems.BLANK_PATTERN.asItem());
        if (blank == null) {
            return 0;
        }
        return storage.getAvailableStacks().get(blank);
    }

    private static GenericStack[] toGenericSparse(List<JsonObject> slots, int maxSlots) {
        int maxIndex = -1;
        List<IndexedStack> indexed = new ArrayList<>();
        int sequential = 0;
        for (JsonObject slot : slots) {
            AEItemKey key = parseSlotKey(slot);
            if (key == null) {
                if (!slot.has("index")) {
                    sequential++;
                }
                continue;
            }
            int index = slot.has("index") && slot.get("index").isJsonPrimitive()
                    ? slot.get("index").getAsInt()
                    : sequential++;
            if (index < 0 || index >= maxSlots) {
                continue;
            }
            indexed.add(new IndexedStack(index, new GenericStack(key, amountOf(slot, 1))));
            maxIndex = Math.max(maxIndex, index);
        }
        if (maxIndex < 0) {
            return new GenericStack[0];
        }
        GenericStack[] arr = new GenericStack[maxIndex + 1];
        for (IndexedStack is : indexed) {
            arr[is.index] = is.stack;
        }
        return arr;
    }

    private record IndexedStack(int index, GenericStack stack) {
    }

    private static boolean hasAny(GenericStack[] arr) {
        if (arr == null) {
            return false;
        }
        for (GenericStack s : arr) {
            if (s != null) {
                return true;
            }
        }
        return false;
    }

    private static AEItemKey requireKey(List<JsonObject> inputs, int index, String label) {
        AEItemKey key = parseSlotKey(at(inputs, index));
        if (key == null) {
            throw new IllegalStateException("invalid_recipe:Missing " + label);
        }
        return key;
    }

    private static JsonObject at(List<JsonObject> list, int index) {
        if (index < 0 || index >= list.size()) {
            return new JsonObject();
        }
        // Prefer explicit index field match for smithing/stonecutting
        for (JsonObject o : list) {
            if (o.has("index") && o.get("index").isJsonPrimitive() && o.get("index").getAsInt() == index) {
                return o;
            }
        }
        return list.get(index);
    }

    private static AEItemKey parseSlotKey(JsonObject slot) {
        if (slot == null) {
            return null;
        }
        String key = str(slot, "key");
        if (key.isEmpty()) {
            String id = str(slot, "id");
            if (id.isEmpty()) {
                return null;
            }
            key = id.startsWith("item:") ? id : "item:" + id;
        }
        return Ae2QueryService.parseItemKey(key);
    }

    private static String slotRef(JsonObject slot) {
        if (slot == null) {
            return null;
        }
        String key = str(slot, "key");
        if (!key.isEmpty()) {
            return key;
        }
        String id = str(slot, "id");
        return id.isEmpty() ? null : id;
    }

    private static long amountOf(JsonObject slot, long fallback) {
        if (slot == null || !slot.has("amount") || !slot.get("amount").isJsonPrimitive()) {
            return fallback;
        }
        try {
            long v = Long.parseLong(slot.get("amount").getAsString().trim());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<JsonObject> inputsOf(JsonObject body) {
        return jsonObjectList(body, "inputs");
    }

    private static List<JsonObject> outputsOf(JsonObject body) {
        return jsonObjectList(body, "outputs");
    }

    private static List<JsonObject> jsonObjectList(JsonObject body, String field) {
        List<JsonObject> out = new ArrayList<>();
        if (body == null || !body.has(field) || !body.get(field).isJsonArray()) {
            return out;
        }
        for (JsonElement el : body.getAsJsonArray(field)) {
            if (el != null && el.isJsonObject()) {
                out.add(el.getAsJsonObject());
            }
        }
        return out;
    }

    private static JsonObject previewOk() {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("canEncode", true);
        return root;
    }

    private static JsonObject previewFail(String warning) {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("canEncode", false);
        root.addProperty("warning", warning);
        return root;
    }

    private static JsonObject parseCodedErr(IllegalStateException e) {
        String msg = e.getMessage() == null ? "error" : e.getMessage();
        int colon = msg.indexOf(':');
        if (colon > 0) {
            return err(msg.substring(0, colon), msg.substring(colon + 1));
        }
        return err(msg, msg);
    }

    private static JsonObject err(String code, String message) {
        JsonObject root = new JsonObject();
        root.addProperty("ok", false);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        root.add("error", error);
        return root;
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString().trim() : "";
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) {
            return def;
        }
        try {
            return o.get(key).getAsBoolean();
        } catch (Exception e) {
            return def;
        }
    }
}
