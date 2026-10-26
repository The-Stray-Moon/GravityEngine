package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Immutable clipped sensor volume. Coordinates are relative to its captured
 * origin to retain precision far from the world origin. Not a collision solver. */
public final class ConvexVolume {
    private record Face(Vec3d normal,double offset,List<Vec3d> vertices) {
        Face { vertices=List.copyOf(vertices); }
    }
    private final Vec3d origin;
    private final List<Face> faces;
    private ConvexVolume(Vec3d origin,List<Face> faces) {this.origin=origin;this.faces=List.copyOf(faces);}

    public static ConvexVolume box(OrientedBox box) {
        var axes=List.of(box.orientation().axisX(),box.orientation().axisY(),box.orientation().axisZ());
        var h=box.halfExtents();double[] half={h.x(),h.y(),h.z()};
        var faces=new ArrayList<Face>();
        for(int axis=0;axis<3;axis++) for(int sign:new int[]{-1,1}) {
            var n=axes.get(axis).multiply(sign);var points=new ArrayList<Vec3d>();
            int u=(axis+1)%3,v=(axis+2)%3;
            for(int a:new int[]{-1,1}) for(int b:new int[]{-1,1})
                points.add(n.multiply(half[axis]).add(axes.get(u).multiply(a*half[u])).add(axes.get(v).multiply(b*half[v])));
            faces.add(new Face(n,half[axis],ordered(points,n)));
        }
        return new ConvexVolume(box.center(),faces);
    }
    public boolean contains(Vec3d point) {
        var p=point.subtract(origin);
        return faces.stream().allMatch(f->f.normal.dot(p)<=f.offset+1e-10);
    }
    public double maximum(Vec3d axis) {
        double max=Double.NEGATIVE_INFINITY;
        for(var face:faces) for(var p:face.vertices) max=Math.max(max,p.dot(axis));
        return max+origin.dot(axis);
    }
    public double volume() {
        var vertices=vertices();if(vertices.isEmpty()) return 0;
        var center=Vec3d.ZERO;for(var p:vertices) center=center.add(p);center=center.divide(vertices.size());
        double result=0;
        for(var face:faces) for(int i=1;i+1<face.vertices.size();i++)
            result+=Math.abs(face.vertices.get(0).subtract(center).dot(
                    face.vertices.get(i).subtract(center).cross(face.vertices.get(i+1).subtract(center))))/6;
        return result;
    }
    /** Work estimate available without allocating a flattened vertex list. */
    public int faceVertexCount() {return faces.stream().mapToInt(f->f.vertices.size()).sum();}
    /** Disjoint convex pieces outside the box, at most six for one subtraction. */
    public List<ConvexVolume> subtract(OrientedBox box) {
        var result=new ArrayList<ConvexVolume>();var remaining=this;
        for(var plane:box(box).faces) {
            if(remaining==null) break;
            double offset=plane.offset+plane.normal.dot(box.center().subtract(origin));
            var outside=remaining.clip(plane.normal.multiply(-1),-offset);
            if(outside!=null) result.add(outside);
            remaining=remaining.clip(plane.normal,offset);
        }
        return List.copyOf(result);
    }
    public boolean intersects(CollisionBody body) {
        if(body instanceof OrientedBox box) {
            var intersection=this;
            for(var plane:box(box).faces) {
                intersection=intersection.clip(plane.normal,plane.offset+plane.normal.dot(box.center().subtract(origin)));
                if(intersection==null) return false;
            }
            return true;
        }
        var capsule=(CharacterCapsule)body;
        var a=capsule.a().subtract(origin);var b=capsule.b().subtract(origin);
        double lo=0,hi=1;
        for(var face:faces) {
            double da=face.normal.dot(a)-face.offset,db=face.normal.dot(b)-face.offset;
            if(da>0&&db>0) {lo=2;break;}
            if(da>0) lo=Math.max(lo,da/(da-db));
            if(db>0) hi=Math.min(hi,da/(da-db));
        }
        if(lo<=hi) return true;
        double radius2=capsule.radius()*capsule.radius();
        for(var face:faces) {
            var v=face.vertices;
            for(int i=1;i+1<v.size();i++) {
                if(pointTriangle(a,v.get(0),v.get(i),v.get(i+1))<radius2
                        ||pointTriangle(b,v.get(0),v.get(i),v.get(i+1))<radius2) return true;
            }
            for(int i=0;i<v.size();i++) if(segments(a,b,v.get(i),v.get((i+1)%v.size()))<radius2) return true;
        }
        return false;
    }
    private ConvexVolume clip(Vec3d normal,double offset) {
        var output=new ArrayList<Face>();var cap=new ArrayList<Vec3d>();
        boolean inside=false,outside=false;
        for(var face:faces) for(var p:face.vertices) {
            if(normal.dot(p)-offset>1e-10) outside=true;else inside=true;
        }
        if(!inside) return null;if(!outside) return this;
        for(var face:faces) {
            var points=new ArrayList<Vec3d>();var input=face.vertices;
            for(int i=0;i<input.size();i++) {
                var a=input.get(i);var b=input.get((i+1)%input.size());
                double da=normal.dot(a)-offset,db=normal.dot(b)-offset;
                if(da<=0) addUnique(points,a);
                if((da<0&&db>0)||(da>0&&db<0)) {
                    var crossing=a.lerp(b,da/(da-db));addUnique(points,crossing);addUnique(cap,crossing);
                } else if(Math.abs(da)<=1e-10) addUnique(cap,a);
            }
            if(points.size()>=3) output.add(new Face(face.normal,face.offset,points));
        }
        if(cap.size()>=3) output.add(new Face(normal,offset,ordered(cap,normal)));
        if(output.size()<4) return null;
        var result=new ConvexVolume(origin,output);
        return result.volume()>1e-15?result:null;
    }
    private List<Vec3d> vertices() {
        var result=new ArrayList<Vec3d>();for(var face:faces) for(var p:face.vertices) addUnique(result,p);return result;
    }
    private static void addUnique(List<Vec3d> points,Vec3d value) {
        if(points.stream().noneMatch(p->p.distanceSquared(value)<1e-20)) points.add(value);
    }
    private static List<Vec3d> ordered(List<Vec3d> input,Vec3d normal) {
        var center=Vec3d.ZERO;for(var p:input) center=center.add(p);final var c=center.divide(input.size());
        var u=normal.cross(Math.abs(normal.x())<.8?new Vec3d(1,0,0):new Vec3d(0,1,0)).normalized();
        var v=normal.cross(u);var result=new ArrayList<>(input);
        result.sort(Comparator.comparingDouble(p->Math.atan2(p.subtract(c).dot(v),p.subtract(c).dot(u))));return result;
    }
    private static double pointTriangle(Vec3d p,Vec3d a,Vec3d b,Vec3d c) {
        var ab=b.subtract(a);var ac=c.subtract(a);var ap=p.subtract(a);
        double d1=ab.dot(ap),d2=ac.dot(ap);if(d1<=0&&d2<=0) return ap.lengthSquared();
        var bp=p.subtract(b);double d3=ab.dot(bp),d4=ac.dot(bp);if(d3>=0&&d4<=d3) return bp.lengthSquared();
        double vc=d1*d4-d3*d2;if(vc<=0&&d1>=0&&d3<=0) return p.distanceSquared(a.fma(d1/(d1-d3),ab));
        var cp=p.subtract(c);double d5=ab.dot(cp),d6=ac.dot(cp);if(d6>=0&&d5<=d6) return cp.lengthSquared();
        double vb=d5*d2-d1*d6;if(vb<=0&&d2>=0&&d6<=0) return p.distanceSquared(a.fma(d2/(d2-d6),ac));
        double va=d3*d6-d5*d4;if(va<=0&&d4-d3>=0&&d5-d6>=0)
            return p.distanceSquared(b.fma((d4-d3)/((d4-d3)+(d5-d6)),c.subtract(b)));
        double sum=va+vb+vc;
        if(sum==0) return Math.min(segments(p,p,a,b),Math.min(segments(p,p,b,c),segments(p,p,c,a)));
        return p.distanceSquared(a.fma(vb/sum,ab).fma(vc/sum,ac));
    }
    private static double segments(Vec3d p,Vec3d q,Vec3d a,Vec3d b) {
        var d=q.subtract(p);var e=b.subtract(a);var r=p.subtract(a);
        double dd=d.dot(d),ee=e.dot(e),de=d.dot(e),dr=d.dot(r),er=e.dot(r),s=0,t=0;
        if(dd<=1e-30) {t=ee<=1e-30?0:clamp(er/ee);}
        else if(ee<=1e-30) s=clamp(-dr/dd);
        else {
            double denominator=dd*ee-de*de;
            if(denominator>1e-30) s=clamp((de*er-dr*ee)/denominator);
            t=(de*s+er)/ee;
            if(t<0) {t=0;s=clamp(-dr/dd);}else if(t>1) {t=1;s=clamp((de-dr)/dd);}
        }
        return p.fma(s,d).distanceSquared(a.fma(t,e));
    }
    private static double clamp(double value) {return Math.max(0,Math.min(1,value));}
}
