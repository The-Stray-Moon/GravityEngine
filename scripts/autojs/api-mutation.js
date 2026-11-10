(function(){
    function C(n){return Java.loadClass(n);}
    var Api=C('cc.sighs.gravityengine.api.GravityEngineApi'),V=C('cc.sighs.gravityengine.api.math.Vec3d');
    var level=AutoJS.level(),e=null,map=null,entry=null,original=null;
    var r={runId:__GE_RUN.id,status:'FAIL',cleanup:'NOT RUN',checks:[],
        scope:'live server public entity mutations; temporary provider callback replacement restored in finally'};
    function check(name,ok){r.checks.push({name:name,status:ok?'PASS':'FAIL'});if(!ok)throw Error(name);}
    function rejects(f){try{f();return false;}catch(error){return true;}}
    try{
        e=C('net.minecraft.world.entity.EntityType').ITEM.create(level);
        e.setPos(0,240,0);
        var initial=Api.setDirectGravity(e,new V(0,-2,0),.12),snapshot=Api.entityGravity(e).get();
        check('DIRECT normalized and committed',initial.assignmentChanged()&&String(snapshot.authority())=='DIRECT'&&snapshot.assignedDirection().y()==-1&&snapshot.appliedStrength()==.12);
        check('repeat is idempotent',!Api.setDirectGravity(e,new V(0,-1,0),.12).assignmentChanged());
        check('negative rejected',rejects(function(){Api.setDirectGravity(e,new V(0,-1,0),-1);}));
        check('zero direction rejected',rejects(function(){Api.setDirectGravity(e,V.ZERO,0);}));
        Api.setDirectGravity(e,new V(1,0,0),0);
        check('explicit zero retained',Api.entityGravity(e).get().assignedStrength()==0&&String(Api.entityGravity(e).get().authority())=='DIRECT');
        var rt=C('cc.sighs.gravityengine.gravity.field.GravityFieldRuntime').get(level);
        map=C('org.apache.commons.lang3.reflect.FieldUtils').readField(rt,'providers',true);
        entry=C('org.apache.commons.lang3.reflect.MethodUtils').invokeMethod(map,'entrySet').iterator().next();original=entry.getValue();
        var Provider=C('cc.sighs.gravityengine.api.GravityFieldProvider');
        var Result=C('cc.sighs.gravityengine.api.field.GravityFieldProviderResult');
        var Coverage=C('cc.sighs.gravityengine.api.field.FieldCoverage'),List=C('java.util.List');
        var partial=new (C('cc.sighs.gravityengine.api.field.GravityContribution'))('examplemod:partial','examplemod:test',0,0,0,new V(0,-.3,0),C('cc.sighs.gravityengine.api.field.GravityFieldCompositionMode').ADDITIVE,0);
        entry.setValue(new Provider({evaluate:function(q){return new Result(Coverage.INCOMPLETE,List.of(partial));}}));
        var blocked=Api.releaseDirectGravity(e);
        check('partial-positive release retains DIRECT',String(blocked.status())=='FIELD_INCOMPLETE'&&!blocked.assignmentChanged()&&String(Api.entityGravity(e).get().authority())=='DIRECT'&&Api.entityGravity(e).get().assignedStrength()==0);
        entry.setValue(new Provider({evaluate:function(q){Api.setDirectGravity(e,new V(0,-1,0),1);return new Result(Coverage.COMPLETE,List.of());}}));
        check('callback recursion rejected',rejects(function(){Api.releaseDirectGravity(e);}));
        check('exception retains original DIRECT',String(Api.entityGravity(e).get().authority())=='DIRECT'&&Api.entityGravity(e).get().assignedStrength()==0);
        entry.setValue(new Provider({evaluate:function(q){return new Result(Coverage.COMPLETE,List.of(partial));}}));
        var released=Api.releaseDirectGravity(e);
        check('complete-present release applies field',released.assignmentChanged()&&String(Api.entityGravity(e).get().authority())=='FIELD'&&Math.abs(Api.entityGravity(e).get().assignedStrength()-.3)<1e-9);
        check('already FIELD no-op',!Api.releaseDirectGravity(e).assignmentChanged());
        Api.setDirectGravity(e,new V(0,-1,0),.08);
        entry.setValue(new Provider({evaluate:function(q){return new Result(Coverage.COMPLETE,List.of());}}));
        Api.releaseDirectGravity(e);
        check('complete-empty releases explicit default',String(Api.entityGravity(e).get().authority())=='FIELD'&&String(Api.entityGravity(e).get().fieldPresence())=='ABSENT');
        e.discard();check('removed rejected',rejects(function(){Api.setDirectGravity(e,new V(0,-1,0),.1);}));
        r.status='PASS';
    }catch(error){r.error=String(error);}
    finally{if(entry!=null&&original!=null)entry.setValue(original);if(e!=null&&!e.isRemoved())e.discard();r.cleanup='PASS';}
    return JSON.stringify(r);
})();
