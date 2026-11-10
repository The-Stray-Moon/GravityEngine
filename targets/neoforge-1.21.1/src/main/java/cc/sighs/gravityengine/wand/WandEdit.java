package cc.sighs.gravityengine.wand;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One immutable edit authority. World bounds are derived, never separately stored.
 * Quarter turns use Create 6.0.10's clockwise wheel convention and parity pivot.
 * Face edits rebase the geometry; a symmetric source's mirrors change only orientation. */
public record WandEdit(AABB base, int turns, boolean mirrorX, boolean mirrorZ, Vec3 offset) {
    public static WandEdit of(AABB box) { return new WandEdit(box,0,false,false,Vec3.ZERO); }
    public Vec3 pivot() {
        double x=base.getXsize(), z=base.getZsize();
        boolean parity=(int)x%2!=(int)z%2;
        return base.getCenter().add(parity&&x<=z?.5:0,0,parity&&x>z?.5:0);
    }
    public Vec3 transform(Vec3 point) {
        var c=base.getCenter();
        var p=new Vec3(mirrorX?2*c.x-point.x:point.x,point.y,mirrorZ?2*c.z-point.z:point.z);
        var pivot=pivot(); var v=p.subtract(pivot);
        for(int i=0;i<Math.floorMod(turns,4);i++) v=new Vec3(-v.z,v.y,v.x);
        return pivot.add(v).add(offset);
    }
    public AABB bounds() {
        return new AABB(transform(new Vec3(base.minX,base.minY,base.minZ)),transform(new Vec3(base.maxX,base.maxY,base.maxZ)));
    }
    public WandEdit rotate(int steps) { return new WandEdit(base,Math.floorMod(turns+steps,4),mirrorX,mirrorZ,offset); }
    public WandEdit move(Vec3 delta) { return new WandEdit(base,turns,mirrorX,mirrorZ,offset.add(delta)); }
    public WandEdit resize(Direction face,int steps) { return of(WandSelection.moveFace(bounds(),face,steps)); }
    public WandEdit mirror(Direction face) {
        if(face.getAxis()==Direction.Axis.Y)return this;
        // Create flips across the vertical plane containing the picked side normal.
        boolean x=face.getAxis()==Direction.Axis.Z;
        if((turns&1)!=0)x=!x;
        return new WandEdit(base,turns,x?!mirrorX:mirrorX,x?mirrorZ:!mirrorZ,offset);
    }
    public boolean valid() {
        return turns>=0&&turns<4&&WandFields.valid(base,0,1)&&WandFields.valid(bounds(),0,1)
            &&Double.isFinite(offset.x)&&Double.isFinite(offset.y)&&Double.isFinite(offset.z);
    }
    public CompoundTag save() {
        var t=new CompoundTag();
        t.putDouble("X0",base.minX);t.putDouble("Y0",base.minY);t.putDouble("Z0",base.minZ);
        t.putDouble("X1",base.maxX);t.putDouble("Y1",base.maxY);t.putDouble("Z1",base.maxZ);
        t.putInt("Turns",turns);t.putBoolean("MirrorX",mirrorX);t.putBoolean("MirrorZ",mirrorZ);
        t.putDouble("DX",offset.x);t.putDouble("DY",offset.y);t.putDouble("DZ",offset.z);return t;
    }
    public static WandEdit load(CompoundTag t) {
        var edit=new WandEdit(new AABB(t.getDouble("X0"),t.getDouble("Y0"),t.getDouble("Z0"),t.getDouble("X1"),t.getDouble("Y1"),t.getDouble("Z1")),
            t.getInt("Turns"),t.getBoolean("MirrorX"),t.getBoolean("MirrorZ"),new Vec3(t.getDouble("DX"),t.getDouble("DY"),t.getDouble("DZ")));
        if(!edit.valid())throw new IllegalArgumentException("Invalid wand edit");return edit;
    }
}
