package cc.sighs.gravityengine.wand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import java.util.List;

/** Client preview only. The server attachment owns confirmed drafts. */
public final class WandClient {
    private static final Minecraft MC=Minecraft.getInstance();
    private static Object level,player;
    private static boolean held,dirty,awaiting;
    private enum Mode {
        MOVE_FACE("move_face"),MOVE_ALL("move_all"),ROTATE("rotate"),MIRROR("mirror"),DEPLOY("deploy");
        final String key;Mode(String key){this.key=key;}
    }
    private static Mode mode=Mode.MOVE_FACE;
    private static double range=10;
    static double strength=.08,fieldRange=32;
    static boolean additive;
    private static WandEdit edit,dragEdit;
    private static Vec3 start,planeNormal;
    private static double depth;
    private static Direction normal=Direction.UP,editingFace;
    private static long lastScroll;
    private static List<WandFields.Field> fields=List.of();
    public static void install(){
        WandPackets.client=p->{context();if(MC.level!=null&&MC.level.dimension().location().equals(p.dimension()))fields=p.fields();};
        WandPackets.clientDraft=p->{
            context();if(MC.level==null||!MC.level.dimension().location().equals(p.dimension()))return;
            edit=p.edit();strength=p.strength();fieldRange=p.range();additive=p.additive();
            cancelGesture();dirty=false;awaiting=false;
        };
    }
    private static void context(){
        if(level==MC.level&&player==MC.player)return;
        level=MC.level;player=MC.player;edit=null;fields=List.of();held=false;dirty=false;awaiting=false;cancelGesture();
        if(MC.screen instanceof WandParameterScreen)MC.setScreen(null);
    }
    private static void cancelGesture(){start=null;dragEdit=null;editingFace=null;depth=0;}
    private static boolean holding(){return MC.player!=null&&MC.player.getMainHandItem().is(GravityWand.ITEM.get());}
    private static boolean active(){return holding()&&MC.screen==null;}
    private static AABB selection(){return edit==null?null:edit.bounds();}
    private static void status(){MC.player.displayClientMessage(Component.translatable("wand.gravityengine.help",Component.translatable("wand.gravityengine."+mode.key),Component.translatable("wand.gravityengine.hint."+mode.key)),true);}
    static void send(int action,WandEdit value,double s,double r,boolean a){
        if(MC.level!=null){awaiting=true;PacketDistributor.sendToServer(new WandPackets.Request(MC.level.dimension().location(),action,value,s,r,a));}
    }
    private static void simple(int action){send(action,null,strength,fieldRange,additive);}
    private static void finish(){
        cancelGesture();if(dirty){dirty=false;send(3,edit,strength,fieldRange,additive);}
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        context();
        if(!holding()){if(held)finish();held=false;return;}
        if(!held){held=true;simple(0);status();}
        if(MC.screen!=null)return;
        if(start!=null){
            var eye=MC.player.getEyePosition();var look=MC.player.getLookAngle();
            var n=dragEdit==null?Vec3.atLowerCornerOf(normal.getNormal()):planeNormal;
            var plane=start.add(n.scale(depth));double denom=look.dot(n);
            if(Math.abs(denom)>1e-5){
                double t=plane.subtract(eye).dot(n)/denom;
                if(t>0&&t<=128){
                    var hit=eye.add(look.scale(t));
                    if(dragEdit!=null){
                        edit=dragEdit.move(WandSelection.snappedDisplacement(start,hit));dirty=true;
                    }else{
                        var origin=BlockPos.containing(plane);var end=BlockPos.containing(hit);
                        var b=new AABB(Vec3.atLowerCornerOf(origin),new Vec3(normal.getAxis()==Direction.Axis.X?origin.getX():end.getX(),
                            normal.getAxis()==Direction.Axis.Y?origin.getY():end.getY(),normal.getAxis()==Direction.Axis.Z?origin.getZ():end.getZ()));
                        if(b.getXsize()<=64&&b.getYsize()<=64&&b.getZsize()<=64){edit=WandEdit.of(b);dirty=true;}
                    }
                }
            }
        }else if(dirty&&(!Screen.hasControlDown()||System.nanoTime()-lastScroll>250_000_000L))finish();
        if(MC.player.tickCount%40==0)status();
    }
    @SubscribeEvent public static void scroll(InputEvent.MouseScrollingEvent event){
        if(handleScroll(event.getScrollDeltaY(),Screen.hasAltDown(),Screen.hasControlDown()))event.setCanceled(true);
    }
    public static boolean handleScroll(double delta,boolean alt,boolean control){
        if(!active()||!Double.isFinite(delta)||delta==0)return false;
        int step=delta>0?1:-1;
        if(alt){finish();mode=Mode.values()[Math.floorMod(mode.ordinal()+step,Mode.values().length)];status();return true;}
        if(!control)return false;
        if(awaiting)return true;
        if(start!=null){depth=Math.clamp(depth+step,-100,100);return true;}
        if(edit==null||mode==Mode.DEPLOY)return true;
        if(System.nanoTime()-lastScroll>250_000_000L)editingFace=null;
        lastScroll=System.nanoTime();
        if(mode==Mode.ROTATE){edit=edit.rotate(step);dirty=true;feedback("rotated");return true;}
        if(editingFace==null)editingFace=face(selection());
        if(editingFace==null)return true;
        if(mode==Mode.MIRROR){
            if(editingFace.getAxis()!=Direction.Axis.Y){edit=edit.mirror(editingFace);dirty=true;feedback("mirrored");}
            return true;
        }
        int amount=selection().contains(MC.player.getEyePosition())?step:-step;
        edit=mode==Mode.MOVE_ALL?edit.move(Vec3.atLowerCornerOf(editingFace.getNormal()).scale(amount)):edit.resize(editingFace,amount);
        dirty=true;return true;
    }
    private static void feedback(String key){MC.player.displayClientMessage(Component.translatable("wand.gravityengine."+key),true);}
    @SubscribeEvent public static void mouse(InputEvent.MouseButton.Pre event){
        if(!active()||(event.getButton()!=0&&event.getButton()!=1))return;
        event.setCanceled(true);
        if(awaiting)return;
        if(event.getButton()==0){if(event.getAction()==GLFW.GLFW_PRESS){finish();simple(2);}return;}
        if(event.getAction()==GLFW.GLFW_RELEASE){finish();return;}
        if(event.getAction()!=GLFW.GLFW_PRESS)return;
        if(Screen.hasShiftDown()){cancelGesture();edit=null;dirty=false;simple(4);return;}
        if(dirty){finish();return;}
        if(mode==Mode.DEPLOY){if(edit!=null)MC.setScreen(new WandParameterScreen(edit));return;}
        editingFace=null;depth=0;
        if(edit!=null){
            var eye=MC.player.getEyePosition();var look=MC.player.getLookAngle();
            // The grabbed point belongs to the picked source (including degenerate shapes).
            start=WandSelection.pickPoint(selection(),eye,look);
            if(start==null)return;
            planeNormal=look;dragEdit=edit;return;
        }
        var hit=MC.player.pick(100,1,false);
        if(hit instanceof BlockHitResult block&&hit.getType()==HitResult.Type.BLOCK){
            normal=block.getDirection();var h=block.getLocation();var b=block.getBlockPos();
            start=new Vec3(normal.getAxis()==Direction.Axis.X?b.getX()+(normal==Direction.EAST?1:0):Math.floor(h.x),
                normal.getAxis()==Direction.Axis.Y?b.getY()+(normal==Direction.UP?1:0):Math.floor(h.y),
                normal.getAxis()==Direction.Axis.Z?b.getZ()+(normal==Direction.SOUTH?1:0):Math.floor(h.z));
        }else{
            var look=MC.player.getLookAngle();normal=Direction.getNearest(look.x,look.y,look.z);
            start=Vec3.atLowerCornerOf(BlockPos.containing(MC.player.getEyePosition().add(look.scale(range))));
        }
        dragEdit=null;edit=WandEdit.of(new AABB(start,start));dirty=true;
    }
    private static Direction face(AABB b){return WandSelection.pickFace(b,MC.player.getEyePosition(),MC.player.getLookAngle());}
    @SubscribeEvent public static void interaction(InputEvent.InteractionKeyMappingTriggered e){
        if(active()&&(e.isAttack()||e.isUseItem())){e.setCanceled(true);e.setSwingHand(false);}
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event){
        if(!holding()||event.getStage()!=RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)return;
        var pose=event.getPoseStack();var camera=event.getCamera().getPosition();
        var buffers=MC.renderBuffers().bufferSource();var lines=buffers.getBuffer(net.minecraft.client.renderer.RenderType.lines());
        pose.pushPose();
        try{
            pose.translate(-camera.x,-camera.y,-camera.z);
            for(var f:fields)net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose,lines,f.box().inflate(.015),0.5f,0.3f,1f,0.8f);
            var selection=selection();
            if(selection!=null){
                net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose,lines,selection.inflate(.015),0.3f,0.8f,1f,1f);
                var face=editingFace!=null?editingFace:face(selection);
                if(mode==Mode.DEPLOY||MC.screen instanceof WandParameterScreen)arrows(pose,lines,selection);
                if(face!=null){
                    var b=selection;double x0=b.minX,y0=b.minY,z0=b.minZ,x1=b.maxX,y1=b.maxY,z1=b.maxZ;
                    switch(face){case WEST->x1=x0;case EAST->x0=x1;case DOWN->y1=y0;case UP->y0=y1;case NORTH->z1=z0;case SOUTH->z0=z1;}
                    if(mode==Mode.MIRROR&&face.getAxis()!=Direction.Axis.Y){
                        x0=selection.minX-1;x1=selection.maxX+1;y0=selection.minY-1;y1=selection.maxY+1;z0=selection.minZ-1;z1=selection.maxZ+1;
                        if(face.getAxis()==Direction.Axis.X)z0=z1=selection.getCenter().z;else x0=x1=selection.getCenter().x;
                    }
                    net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose,lines,new AABB(x0,y0,z0,x1,y1,z1).inflate(.005),1f,0.8f,0.2f,1f);
                }
            }
        }finally{pose.popPose();}
        buffers.endBatch(net.minecraft.client.renderer.RenderType.lines());
    }
    private static void line(com.mojang.blaze3d.vertex.PoseStack pose,com.mojang.blaze3d.vertex.VertexConsumer lines,Vec3 a,Vec3 b){
        var n=b.subtract(a).normalize();var p=pose.last();
        lines.addVertex(p,(float)a.x,(float)a.y,(float)a.z).setColor(1f,.75f,.1f,1f).setNormal(p,(float)n.x,(float)n.y,(float)n.z);
        lines.addVertex(p,(float)b.x,(float)b.y,(float)b.z).setColor(1f,.75f,.1f,1f).setNormal(p,(float)n.x,(float)n.y,(float)n.z);
    }
    private static void arrows(com.mojang.blaze3d.vertex.PoseStack pose,com.mojang.blaze3d.vertex.VertexConsumer lines,AABB b){
        double s=strength,r=fieldRange;
        if(MC.screen instanceof WandParameterScreen screen){s=screen.previewStrength();r=screen.previewRange();}
        if(!WandFields.valid(b,s,r)||s==0)return;
        var f=new WandFields.Field(new java.util.UUID(0,0),new java.util.UUID(0,0),b,s,r,cc.sighs.gravityengine.api.field.GravityFieldCompositionMode.OVERRIDE).evaluator();
        var c=b.getCenter();double distance=Math.min(2,r*.5);
        for(var d:Direction.values()){
            var n=Vec3.atLowerCornerOf(d.getNormal());
            double half=switch(d.getAxis()){case X->b.getXsize()/2;case Y->b.getYsize()/2;case Z->b.getZsize()/2;};
            var a=c.add(n.scale(half+distance));
            var v=f.sample(cc.sighs.gravityengine.api.field.GravityFieldQuery.at(new cc.sighs.gravityengine.api.math.Vec3d(a.x,a.y,a.z))).acceleration();
            if(v.lengthSquared()==0)continue;
            var direction=new Vec3(v.x(),v.y(),v.z()).normalize();var end=a.add(direction.scale(Math.min(1,distance*.75)));
            var side=direction.cross(Math.abs(direction.y)<.9?new Vec3(0,1,0):new Vec3(1,0,0)).normalize().scale(.15);
            line(pose,lines,a,end);line(pose,lines,end,end.subtract(direction.scale(.25)).add(side));line(pose,lines,end,end.subtract(direction.scale(.25)).subtract(side));
        }
    }
}
