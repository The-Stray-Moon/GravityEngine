package cc.sighs.gravityengine.wand;

import cc.sighs.gravityengine.GravityEngineAttachments;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.INBTSerializable;
import java.util.*;

/** Server attachment: per-player/dimension drafts and player-wide confirmed parameters.
 * Native attachment serialization and copyOnDeath own persistence and replacement. */
public final class WandDrafts implements INBTSerializable<CompoundTag> {
    private final Map<ResourceLocation,WandEdit> drafts=new HashMap<>();
    private double strength=.08,range=32;
    private boolean additive;
    public static WandDrafts get(Player player){return player.getData(GravityEngineAttachments.WAND_DRAFTS);}
    public WandEdit get(ResourceLocation dimension){return drafts.get(dimension);}
    public double strength(){return strength;}
    public double range(){return range;}
    public boolean additive(){return additive;}
    public void put(ResourceLocation dimension,WandEdit edit){if(edit==null)drafts.remove(dimension);else drafts.put(dimension,edit);}
    public void parameters(double strength,double range,boolean additive){this.strength=strength;this.range=range;this.additive=additive;}
    @Override public CompoundTag serializeNBT(HolderLookup.Provider registries){
        var tag=new CompoundTag();tag.putDouble("Strength",strength);tag.putDouble("Range",range);tag.putBoolean("Additive",additive);
        var list=new ListTag();drafts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{var t=e.getValue().save();t.putString("Dimension",e.getKey().toString());list.add(t);});
        tag.put("Drafts",list);return tag;
    }
    @Override public void deserializeNBT(HolderLookup.Provider registries,CompoundTag tag){
        if (!tag.getAllKeys().equals(Set.of("Strength","Range","Additive","Drafts"))
                || !tag.contains("Strength",Tag.TAG_DOUBLE) || !tag.contains("Range",Tag.TAG_DOUBLE)
                || !tag.contains("Additive",Tag.TAG_BYTE) || !tag.contains("Drafts",Tag.TAG_LIST))
            throw new IllegalArgumentException("Invalid wand draft record");
        var storedList = (ListTag) tag.get("Drafts");
        if (!storedList.isEmpty() && storedList.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid Drafts list element type");
        double s=tag.getDouble("Strength"),r=tag.getDouble("Range");
        if(!WandFields.valid(new net.minecraft.world.phys.AABB(0,0,0,0,0,0),s,r))throw new IllegalArgumentException("Invalid wand parameters");
        var restored=new HashMap<ResourceLocation,WandEdit>();var list=tag.getList("Drafts",Tag.TAG_COMPOUND);
        for(int i=0;i<list.size();i++){var t=list.getCompound(i);var id=ResourceLocation.parse(t.getString("Dimension"));
            if(restored.put(id,WandEdit.load(t))!=null)throw new IllegalArgumentException("Duplicate wand dimension");}
        drafts.clear();drafts.putAll(restored);parameters(s,r,tag.getBoolean("Additive"));
    }
}
