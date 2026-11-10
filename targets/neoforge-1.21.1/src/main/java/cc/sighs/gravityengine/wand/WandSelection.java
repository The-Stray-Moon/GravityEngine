package cc.sighs.gravityengine.wand;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Editing geometry only. Picking tolerance never changes the actual source. */
public final class WandSelection {
    private WandSelection() {}
    public static Vec3 snappedDisplacement(Vec3 start, Vec3 end) {
        var d=end.subtract(start);return new Vec3(Math.round(d.x),Math.round(d.y),Math.round(d.z));
    }
    private record Pick(Direction face, Vec3 point) {}
    public static Direction pickFace(AABB source, Vec3 eye, Vec3 direction) {
        var picked=pick(source,eye,direction);return picked==null?null:picked.face();
    }
    public static Vec3 pickPoint(AABB source, Vec3 eye, Vec3 direction) {
        var picked=pick(source,eye,direction);return picked==null?null:picked.point();
    }
    private static Pick pick(AABB source, Vec3 eye, Vec3 direction) {
        double[] min={source.minX,source.minY,source.minZ}, max={source.maxX,source.maxY,source.maxZ};
        double[] origin={eye.x,eye.y,eye.z}, ray={direction.x,direction.y,direction.z};
        Direction[] negative={Direction.WEST,Direction.DOWN,Direction.NORTH};
        Direction[] positive={Direction.EAST,Direction.UP,Direction.SOUTH};
        double enter=Double.NEGATIVE_INFINITY, exit=Double.POSITIVE_INFINITY;
        Direction nearFace=null, farFace=null;
        for(int axis=0;axis<3;axis++) {
            if(min[axis]==max[axis]) { min[axis]-=0.15; max[axis]+=0.15; }
            if(Math.abs(ray[axis])<1e-10) {
                if(origin[axis]<min[axis]||origin[axis]>max[axis])return null;
                continue;
            }
            double first=(min[axis]-origin[axis])/ray[axis];
            double last=(max[axis]-origin[axis])/ray[axis];
            Direction firstFace=negative[axis],lastFace=positive[axis];
            if(first>last){double swap=first;first=last;last=swap;firstFace=positive[axis];lastFace=negative[axis];}
            if(first>enter){enter=first;nearFace=firstFace;}
            if(last<exit){exit=last;farFace=lastFace;}
            if(enter>exit)return null;
        }
        if(exit<0)return null;
        double distance=enter>=0?enter:exit;
        return distance<=128?new Pick(enter>=0?nearFace:farFace,eye.add(direction.scale(distance))):null;
    }
    public static AABB moveFace(AABB source, Direction face, int outwardSteps) {
        double[] min={source.minX,source.minY,source.minZ}, max={source.maxX,source.maxY,source.maxZ};
        int axis=switch(face.getAxis()){case X->0;case Y->1;case Z->2;};
        if(face.getAxisDirection()==Direction.AxisDirection.POSITIVE)
            max[axis]=Math.max(min[axis],Math.min(min[axis]+64,max[axis]+outwardSteps));
        else min[axis]=Math.min(max[axis],Math.max(max[axis]-64,min[axis]-outwardSteps));
        return new AABB(min[0],min[1],min[2],max[0],max[1],max[2]);
    }
}
