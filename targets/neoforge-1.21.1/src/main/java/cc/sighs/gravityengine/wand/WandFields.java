package cc.sighs.gravityengine.wand;

import cc.sighs.gravityengine.api.*;
import cc.sighs.gravityengine.api.field.*;
import cc.sighs.gravityengine.api.math.Vec3d;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** The dimension SavedData is the sole source authority, including unloaded chunks.
 * Loaded synchronously before provider evaluation; a successfully loaded empty list proves
 * absence only for this provider. Client copies are presentation only. */
public final class WandFields extends SavedData {
    public record Field(UUID id, UUID owner, AABB box, double strength, double range, GravityFieldCompositionMode mode) {
        public cc.sighs.gravityengine.gravity.field.NearestSourceGravityField evaluator() {
            return new cc.sighs.gravityengine.gravity.field.NearestSourceGravityField(
                new Vec3d(box.minX, box.minY, box.minZ), new Vec3d(box.maxX, box.maxY, box.maxZ), strength, range);
        }
    }
    final List<Field> fields = new ArrayList<>();
    public static WandFields get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(WandFields::new, WandFields::load, null), "gravityengine_wand_fields");
    }
    public List<Field> snapshot() { return List.copyOf(fields); }
    public static GravityFieldProvider provider(ServerLevel level) {
        return new GravityFieldProvider() {
            public GravityFieldProviderResult evaluate(GravityFieldQuery query) {
                var point = query.position();
                var contributions = get(level).fields.stream()
                    .filter(f -> f.evaluator().influences(point))
                    .map(f -> new GravityContribution("gravityengine:wand/" + f.id,
                        "gravityengine:wand", 0, 0, 0, f.evaluator().sample(query).acceleration(), f.mode, 0)).toList();
                return new GravityFieldProviderResult(FieldCoverage.COMPLETE, contributions);
            }
            public GravityFieldDiscovery blockDiscovery() {
                return new GravityFieldDiscovery(get(level).fields.stream().map(f -> new GravityFieldBounds(
                    f.box.minX-f.range, f.box.minY-f.range, f.box.minZ-f.range, f.box.maxX+f.range, f.box.maxY+f.range, f.box.maxZ+f.range)).toList(), false);
            }
        };
    }
    static boolean valid(AABB b, double strength, double range) {
        return Double.isFinite(b.minX) && Double.isFinite(b.minY) && Double.isFinite(b.minZ)
            && Double.isFinite(b.maxX) && Double.isFinite(b.maxY) && Double.isFinite(b.maxZ)
            && b.getXsize() >= 0 && b.getYsize() >= 0 && b.getZsize() >= 0
            && b.getXsize() <= 64 && b.getYsize() <= 64 && b.getZsize() <= 64
            && Double.isFinite(strength) && strength >= -1 && strength <= 1
            && Double.isFinite(range) && range > 0 && range <= 256;
    }
    public static WandFields load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.getAllKeys().equals(Set.of("Fields")) || !tag.contains("Fields", Tag.TAG_LIST))
            throw new IllegalStateException("Invalid wand fields record");
        var storedList = (ListTag) tag.get("Fields");
        if (!storedList.isEmpty() && storedList.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid Fields list element type");
        var data = new WandFields();
        var list = tag.getList("Fields", Tag.TAG_COMPOUND);
        if (list.size() > 256) throw new IllegalStateException("Too many saved wand fields");
        var ids = new HashSet<UUID>();
        for (int i = 0; i < list.size(); i++) {
            var t = list.getCompound(i);
            if (!t.getAllKeys().equals(Set.of("Id","Owner","X0","Y0","Z0","X1","Y1","Z1","Strength","Range","Mode"))
                    || !t.hasUUID("Id") || !t.hasUUID("Owner") || !t.contains("Mode", Tag.TAG_STRING))
                throw new IllegalStateException("Invalid wand source record");
            for (String key : List.of("X0","Y0","Z0","X1","Y1","Z1","Strength","Range"))
                if (!t.contains(key, Tag.TAG_DOUBLE)) throw new IllegalStateException("Missing numeric wand field: " + key);
            var box = new AABB(t.getDouble("X0"),t.getDouble("Y0"),t.getDouble("Z0"),t.getDouble("X1"),t.getDouble("Y1"),t.getDouble("Z1"));
            double strength = t.getDouble("Strength"), range = t.getDouble("Range");
            var id = t.getUUID("Id");
            if (!valid(box,strength,range) || !ids.add(id)) throw new IllegalStateException("Invalid saved wand field");
            data.fields.add(new Field(id,t.getUUID("Owner"),box,strength,range,GravityFieldCompositionMode.valueOf(t.getString("Mode"))));
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var f : fields) {
            var t = new CompoundTag(); var b = f.box;
            t.putUUID("Id",f.id); t.putUUID("Owner",f.owner);
            t.putDouble("X0",b.minX);t.putDouble("Y0",b.minY);t.putDouble("Z0",b.minZ);
            t.putDouble("X1",b.maxX);t.putDouble("Y1",b.maxY);t.putDouble("Z1",b.maxZ);
            t.putDouble("Strength",f.strength);t.putDouble("Range",f.range);
            t.putString("Mode",f.mode.name());list.add(t);
        }
        tag.put("Fields",list);return tag;
    }
}
