package cc.sighs.gravityengine.wand;

import cc.sighs.gravityengine.api.field.GravityFieldCompositionMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;

public final class WandPackets {
    // Installed by physical client, keeping dedicated-server class loading client-free.
    public static java.util.function.Consumer<Snapshot> client = p -> {};
    public record Request(ResourceLocation dimension, int action, WandEdit edit, double strength, double range, boolean additive) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.parse("gravityengine:wand_request"));
        public static final StreamCodec<FriendlyByteBuf,Request> CODEC = StreamCodec.of((b,p) -> {
            b.writeResourceLocation(p.dimension);b.writeVarInt(p.action);writeEdit(b,p.edit);
            b.writeDouble(p.strength);b.writeDouble(p.range);b.writeBoolean(p.additive);
        }, b -> new Request(b.readResourceLocation(),b.readVarInt(),readEdit(b),b.readDouble(),b.readDouble(),b.readBoolean()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Snapshot(ResourceLocation dimension,List<WandFields.Field> fields) implements CustomPacketPayload {
        public Snapshot { fields = List.copyOf(fields); }
        public static final Type<Snapshot> TYPE = new Type<>(ResourceLocation.parse("gravityengine:wand_snapshot"));
        public static final StreamCodec<FriendlyByteBuf,Snapshot> CODEC = StreamCodec.of((b,p) -> {
            b.writeResourceLocation(p.dimension);b.writeVarInt(p.fields.size());
            for(var f:p.fields){b.writeUUID(f.id());b.writeUUID(f.owner());writeBox(b,f.box());
                b.writeDouble(f.strength());b.writeDouble(f.range());b.writeBoolean(f.mode()==GravityFieldCompositionMode.ADDITIVE);}
        }, b -> {
            var dim=b.readResourceLocation();int n=b.readVarInt();if(n<0||n>256)throw new IllegalArgumentException("field count");
            var fields=new ArrayList<WandFields.Field>();for(int i=0;i<n;i++)fields.add(new WandFields.Field(b.readUUID(),b.readUUID(),readBox(b),b.readDouble(),b.readDouble(),b.readBoolean()?GravityFieldCompositionMode.ADDITIVE:GravityFieldCompositionMode.OVERRIDE));
            return new Snapshot(dim,fields);
        });
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static java.util.function.Consumer<DraftSnapshot> clientDraft = p -> {};
    public record DraftSnapshot(ResourceLocation dimension,WandEdit edit,double strength,double range,boolean additive) implements CustomPacketPayload {
        public static final Type<DraftSnapshot> TYPE=new Type<>(ResourceLocation.parse("gravityengine:wand_draft"));
        public static final StreamCodec<FriendlyByteBuf,DraftSnapshot> CODEC=StreamCodec.of((b,p)->{
            b.writeResourceLocation(p.dimension);writeEdit(b,p.edit);b.writeDouble(p.strength);b.writeDouble(p.range);b.writeBoolean(p.additive);
        },b->new DraftSnapshot(b.readResourceLocation(),readEdit(b),b.readDouble(),b.readDouble(),b.readBoolean()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    static void writeEdit(FriendlyByteBuf b,WandEdit e){
        b.writeBoolean(e!=null);if(e==null)return;
        writeBox(b,e.base());b.writeVarInt(e.turns());b.writeBoolean(e.mirrorX());b.writeBoolean(e.mirrorZ());
        b.writeDouble(e.offset().x);b.writeDouble(e.offset().y);b.writeDouble(e.offset().z);
    }
    static WandEdit readEdit(FriendlyByteBuf b){
        return !b.readBoolean()?null:new WandEdit(readBox(b),b.readVarInt(),b.readBoolean(),b.readBoolean(),new net.minecraft.world.phys.Vec3(b.readDouble(),b.readDouble(),b.readDouble()));
    }
    public static void syncDraft(ServerPlayer player){
        var d=WandDrafts.get(player);var dim=player.serverLevel().dimension().location();
        PacketDistributor.sendToPlayer(player,new DraftSnapshot(dim,d.get(dim),d.strength(),d.range(),d.additive()));
    }
    static void writeBox(FriendlyByteBuf b,AABB a){b.writeDouble(a.minX);b.writeDouble(a.minY);b.writeDouble(a.minZ);b.writeDouble(a.maxX);b.writeDouble(a.maxY);b.writeDouble(a.maxZ);}
    static AABB readBox(FriendlyByteBuf b){return new AABB(b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble());}
    public static void register(RegisterPayloadHandlersEvent e){
        var r=e.registrar(net.neoforged.fml.ModList.get().getModContainerById(cc.sighs.gravityengine.GravityEngine.MOD_ID)
                .orElseThrow().getModInfo().getVersion().toString());
        r.playToServer(Request.TYPE,Request.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer s)receive(s,p);});
        r.playToClient(DraftSnapshot.TYPE,DraftSnapshot.CODEC,(p,c)->clientDraft.accept(p));
        r.playToClient(Snapshot.TYPE,Snapshot.CODEC,(p,c)->client.accept(p));
    }
    public static void receive(ServerPlayer player,Request p){
        var level=player.serverLevel();
        if(!p.dimension.equals(level.dimension().location())){reject(player,"dimension_changed");return;}
        // A finishing edit may follow a hotbar change. It cannot deploy/delete a source.
        if(p.action!=3 && !player.getMainHandItem().is(GravityWand.ITEM.get())){reject(player,"hold_wand");return;}
        var data=WandFields.get(level);
        if(p.action==0){PacketDistributor.sendToPlayer(player,new Snapshot(p.dimension,data.snapshot()));syncDraft(player);return;}
        if(!player.isCreative()){reject(player,"permission");return;}
        if(p.action==3 || p.action==4 || p.action==1){
            var box=p.edit==null?null:p.edit.bounds();
            if((p.action==1&&box==null) || (p.edit!=null&&!p.edit.valid())
                ||!WandFields.valid(box==null?new AABB(0,0,0,0,0,0):box,p.strength,p.range)) {reject(player,"invalid");return;}
            if(box!=null&&(box.getCenter().distanceToSqr(player.position())>128*128
                ||box.minY<level.getMinBuildHeight()||box.maxY>level.getMaxBuildHeight()
                ||!level.getWorldBorder().isWithinBounds(net.minecraft.core.BlockPos.containing(box.minX,box.minY,box.minZ))
                ||!level.getWorldBorder().isWithinBounds(net.minecraft.core.BlockPos.containing(box.maxX,box.maxY,box.maxZ)))) {reject(player,"bounds");return;}
            if(p.action==1&&data.fields.size()>=256){reject(player,"limit");return;}
            var drafts=WandDrafts.get(player);
            drafts.put(p.dimension,p.action==4?null:p.edit);
            if(p.action==1)drafts.parameters(p.strength,p.range,p.additive);
            syncDraft(player);
            if(p.action!=1)return;
            data.fields.add(new WandFields.Field(UUID.randomUUID(),player.getUUID(),box,p.strength,p.range,p.additive?GravityFieldCompositionMode.ADDITIVE:GravityFieldCompositionMode.OVERRIDE));
        }else if(p.action==2){
            var eye=player.getEyePosition();var end=eye.add(player.getLookAngle().scale(128));
            var hit=data.fields.stream().filter(f->f.box().inflate(0.2).contains(eye)||f.box().inflate(0.2).clip(eye,end).isPresent())
                .min(Comparator.comparingDouble(f->f.box().inflate(0.2).contains(eye)?0:f.box().inflate(0.2).clip(eye,end).orElseThrow().distanceToSqr(eye))).orElse(null);
            if(hit==null){reject(player,"no_field");return;}
            if(!hit.owner().equals(player.getUUID())&&!player.hasPermissions(2)){reject(player,"permission");return;}
            data.fields.remove(hit);
            syncDraft(player);
        }else {reject(player,"invalid");return;}
        data.setDirty();message(player,p.action==1?"created":"removed");
        for(var viewer:level.players())PacketDistributor.sendToPlayer(viewer,new Snapshot(p.dimension,data.snapshot()));
    }
    private static void reject(ServerPlayer player,String reason){message(player,reason);syncDraft(player);}
    static void message(ServerPlayer p,String key){p.displayClientMessage(net.minecraft.network.chat.Component.translatable("wand.gravityengine."+key),true);}
}
